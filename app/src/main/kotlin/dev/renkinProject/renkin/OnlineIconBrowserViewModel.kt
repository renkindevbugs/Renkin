package dev.renkinProject.renkin

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.renkinProject.renkin.data.online.IconifyCollection
import dev.renkinProject.renkin.data.online.OnlineIcon
import dev.renkinProject.renkin.data.online.OnlineIconRepository
import dev.renkinProject.renkin.vector.SvgRasterizer
import dev.renkinProject.renkin.vector.SvgVectorImporter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How a browsed icon can be shown: [Editable] parsed by the strict vector importer (tap
 * imports it into the editor), or [PreviewOnly] rendered faithfully by AndroidSVG because
 * the document uses paints the editor can't model (gradients, clips, `<use>`).
 */
sealed interface OnlineIconPreview {
    data class Editable(val svg: SvgVectorImporter.ImportedSvg) : OnlineIconPreview
    data class PreviewOnly(val bitmap: ImageBitmap) : OnlineIconPreview
}

/** Import resolution for a tapped icon — the UI explains each failure differently. */
sealed interface OnlineIconImport {
    data class Imported(val svg: SvgVectorImporter.ImportedSvg) : OnlineIconImport
    data object NotImportable : OnlineIconImport
    data object LoadFailed : OnlineIconImport
}

/** Owns one open Online Icons browsing session; closing the browser explicitly resets it. */
@HiltViewModel
class OnlineIconBrowserViewModel @Inject constructor(
    private val repository: OnlineIconRepository
) : ViewModel() {

    var selectedCollection: IconifyCollection? by mutableStateOf(null)
        private set
    var collections: List<IconifyCollection>? by mutableStateOf(null)
        private set
    var collectionsFailed by mutableStateOf(false)
        private set
    var icons: List<OnlineIcon>? by mutableStateOf(null)
        private set
    var iconsFailed by mutableStateOf(false)
        private set

    var collectionQuery by mutableStateOf("")
        private set
    var category by mutableStateOf<String?>(null)
    var palette by mutableStateOf<Boolean?>(null)
    var iconQuery by mutableStateOf("")

    // Cross-set search (the list screen's field doubles as a global icon search): null while
    // idle/typing, empty list = no matches. Debounced so every keystroke doesn't hit the API.
    var searchResults: List<OnlineIcon>? by mutableStateOf(null)
        private set
    var searchFailed by mutableStateOf(false)
        private set
    var searching by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    /** True once the query is long enough to mean "search icons" rather than "filter sets". */
    val searchActive: Boolean get() = collectionQuery.trim().length >= MIN_SEARCH_LENGTH

    fun onCollectionQueryChange(value: String) {
        collectionQuery = value
        searchJob?.cancel()
        val query = value.trim()
        if (query.length < MIN_SEARCH_LENGTH) {
            searchResults = null
            searchFailed = false
            searching = false
            return
        }
        searching = true
        searchFailed = false
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val results = repository.search(query)
            if (!sessionActive || collectionQuery.trim() != query) return@launch
            searchResults = results
            searchFailed = results == null
            searching = false
        }
    }

    fun retrySearch() = onCollectionQueryChange(collectionQuery)

    var collectionListIndex by mutableIntStateOf(0)
        private set
    var collectionListOffset by mutableIntStateOf(0)
        private set
    var filterRowOffset by mutableIntStateOf(0)
        private set

    private var sessionActive = false
    private val parsedSvgCache = LruCache<String, SvgVectorImporter.ImportedSvg>(PARSED_SVG_COUNT)

    // Rasterised previews for icons the strict importer rejects (gradient/clip documents).
    // Keyed by URL + tint because currentColor is substituted at render time.
    private val rasterCache = object : LruCache<String, ImageBitmap>(RASTER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun beginSession() {
        if (sessionActive) return
        sessionActive = true
        resetSessionState()
        loadCollections()
    }

    fun endSession() {
        sessionActive = false
        resetSessionState()
    }

    fun openCollection(
        collection: IconifyCollection,
        listIndex: Int,
        listOffset: Int,
        filterOffset: Int
    ) {
        collectionListIndex = listIndex
        collectionListOffset = listOffset
        filterRowOffset = filterOffset
        selectedCollection = collection
        iconQuery = ""
        icons = null
        iconsFailed = false
        loadIcons(collection)
    }

    fun backToCollections() {
        selectedCollection = null
        iconQuery = ""
        icons = null
        iconsFailed = false
    }

    fun retryCollections() = loadCollections()

    fun retryIcons() {
        selectedCollection?.let(::loadIcons)
    }

    /**
     * The preview for one icon: the strict import parse when the editor can model it,
     * otherwise a faithful AndroidSVG raster with `currentColor` resolved to [tintArgb],
     * rendered [rasterSize] px on its longest side (tiles use the small default, the detail
     * dialog asks for more). Null when the icon can't be fetched or rendered at all.
     */
    suspend fun preview(
        icon: OnlineIcon,
        tintArgb: Int,
        rasterSize: Int = PREVIEW_RASTER_SIZE
    ): OnlineIconPreview? {
        parsedSvgCache.get(icon.svgUrl)?.let { return OnlineIconPreview.Editable(it) }
        val rasterKey = "${icon.svgUrl}#$tintArgb#$rasterSize"
        rasterCache.get(rasterKey)?.let { return OnlineIconPreview.PreviewOnly(it) }
        val markup = repository.svg(icon) ?: return null
        return withContext(Dispatchers.Default) {
            SvgVectorImporter.parse(markup)?.let { parsed ->
                parsedSvgCache.put(icon.svgUrl, parsed)
                OnlineIconPreview.Editable(parsed)
            } ?: SvgRasterizer.rasterize(markup, rasterSize, tintArgb)
                ?.asImageBitmap()
                ?.let { bitmap ->
                    rasterCache.put(rasterKey, bitmap)
                    OnlineIconPreview.PreviewOnly(bitmap)
                }
        }
    }

    /** The close-up preview for the detail dialog — rasters render at [DETAIL_RASTER_SIZE]. */
    suspend fun detailPreview(icon: OnlineIcon, tintArgb: Int): OnlineIconPreview? =
        preview(icon, tintArgb, DETAIL_RASTER_SIZE)

    /**
     * Full-size raster for "use as image": the icon exactly as its SVG paints it, at the
     * same resolution the upload gallery imports at. Not cached — it's a one-off on confirm.
     */
    suspend fun importImage(icon: OnlineIcon): Bitmap? {
        val markup = repository.svg(icon) ?: return null
        return withContext(Dispatchers.Default) {
            SvgRasterizer.rasterize(markup, IMAGE_IMPORT_SIZE)
        }
    }

    /** Import resolution for a tapped icon: distinguishes "couldn't fetch" from "fetched,
     * but not modellable as editable vector paths" so the UI can explain which happened. */
    suspend fun importIcon(icon: OnlineIcon): OnlineIconImport {
        parsedSvgCache.get(icon.svgUrl)?.let { return OnlineIconImport.Imported(it) }
        val markup = repository.svg(icon) ?: return OnlineIconImport.LoadFailed
        val parsed = withContext(Dispatchers.Default) { SvgVectorImporter.parse(markup) }
            ?: return OnlineIconImport.NotImportable
        parsedSvgCache.put(icon.svgUrl, parsed)
        return OnlineIconImport.Imported(parsed)
    }

    private fun loadCollections() {
        collections = null
        collectionsFailed = false
        viewModelScope.launch {
            val loaded = repository.collections()
            if (!sessionActive) return@launch
            collections = loaded
            collectionsFailed = loaded == null
        }
    }

    private fun loadIcons(collection: IconifyCollection) {
        icons = null
        iconsFailed = false
        viewModelScope.launch {
            val loaded = repository.icons(collection)
            if (!sessionActive || selectedCollection?.prefix != collection.prefix) return@launch
            icons = loaded
            iconsFailed = loaded == null
        }
    }

    private fun resetSessionState() {
        selectedCollection = null
        collections = null
        collectionsFailed = false
        icons = null
        iconsFailed = false
        collectionQuery = ""
        category = null
        palette = null
        iconQuery = ""
        searchJob?.cancel()
        searchJob = null
        searchResults = null
        searchFailed = false
        searching = false
        collectionListIndex = 0
        collectionListOffset = 0
        filterRowOffset = 0
    }

    private companion object {
        const val PARSED_SVG_COUNT = 160
        // 128px covers the 44dp tile on ~3x screens; ~64KB per icon. The cache also holds a
        // few 512px detail renders (~1MB each), so 6MB keeps browsing smooth without
        // competing with real image work for memory.
        const val PREVIEW_RASTER_SIZE = 128
        const val DETAIL_RASTER_SIZE = 512
        const val RASTER_CACHE_BYTES = 6 * 1024 * 1024
        // Same resolution the upload gallery imports at (UploadImageProcessing).
        const val IMAGE_IMPORT_SIZE = 1024
        const val MIN_SEARCH_LENGTH = 2
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}
