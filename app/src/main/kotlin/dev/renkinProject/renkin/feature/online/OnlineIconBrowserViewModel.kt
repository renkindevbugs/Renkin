package dev.renkinProject.renkin.feature.online

import android.content.Context
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
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.renkinProject.renkin.data.UploadedImageStore
import dev.renkinProject.renkin.data.online.CollapsedSetCategories
import dev.renkinProject.renkin.data.online.DownloadedIconSet
import dev.renkinProject.renkin.data.online.DownloadedIconSets
import dev.renkinProject.renkin.data.online.IconSetDownloadResult
import dev.renkinProject.renkin.data.online.IconSetRelease
import dev.renkinProject.renkin.data.online.IconSetReleaseLookup
import dev.renkinProject.renkin.data.online.IconifyCollection
import dev.renkinProject.renkin.data.online.OnlineIcon
import dev.renkinProject.renkin.data.online.OnlineIconRepository
import dev.renkinProject.renkin.util.Log
import dev.renkinProject.renkin.util.runCatchingCancellable
import dev.renkinProject.renkin.vector.SvgRasterizer
import dev.renkinProject.renkin.vector.SvgVectorImporter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
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

internal sealed interface OnlineIconCachedPreview {
    data object Missing : OnlineIconCachedPreview
    data object Failed : OnlineIconCachedPreview
    data class Ready(val preview: OnlineIconPreview) : OnlineIconCachedPreview
}

/** Import resolution for a tapped icon — the UI explains each failure differently. */
sealed interface OnlineIconImport {
    data class Imported(val svg: SvgVectorImporter.ImportedSvg) : OnlineIconImport
    data object NotImportable : OnlineIconImport
    data object LoadFailed : OnlineIconImport
}

/**
 * An online icon imported "as image": the full-size raster plus, when the disk write
 * succeeded, the upload-gallery copy it was saved as (so the picture stays reusable like
 * any uploaded image).
 */
data class OnlineImageImport(val bitmap: Bitmap, val galleryPath: String?)

sealed interface SetDownloadState {
    /** Asking jsDelivr for the newest release and its size. */
    data object Preparing : SetDownloadState
    data class Downloading(val progress: Float) : SetDownloadState
    data class Failed(val reason: SetDownloadFailure) : SetDownloadState
}

enum class SetDownloadFailure { Network, Damaged, NotAvailable }

/** The confirmation for downloading [set]; [installed] is set when this replaces a download. */
data class SetDownloadOffer(
    val set: IconifyCollection,
    val release: IconSetRelease,
    val installed: DownloadedIconSet?
) {
    val upToDate: Boolean get() = installed?.version == release.version
}

/** Owns one open Online Icons browsing session; closing the browser explicitly resets it. */
@HiltViewModel
class OnlineIconBrowserViewModel @Inject constructor(
    private val repository: OnlineIconRepository,
    @param:ApplicationContext private val context: Context,
    private val collapsedStore: CollapsedSetCategories,
    private val downloads: DownloadedIconSets
) : ViewModel() {

    /** Collapsed set-list categories; remembered across sessions, so not reset on close. */
    var collapsedCategories by mutableStateOf<Set<String>>(emptySet())
        private set

    private var previewSources by mutableStateOf<Map<String, DownloadedIconSet>>(emptyMap())

    init {
        viewModelScope.launch { collapsedCategories = collapsedStore.read() }
    }

    fun previewRevision(prefix: String): String? = previewSources[prefix]?.version

    fun toggleCategory(category: String) {
        val next = if (category in collapsedCategories) collapsedCategories - category
            else collapsedCategories + category
        collapsedCategories = next
        viewModelScope.launch { collapsedStore.write(next) }
    }

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
        searchJob = launchInSession { started ->
            delay(SEARCH_DEBOUNCE_MS)
            val results = repository.search(query)
            if (!isCurrent(started) || collectionQuery.trim() != query) return@launchInSession
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

    val rateLimitUntil = repository.rateLimitUntil

    fun isBuiltIn(set: IconifyCollection): Boolean = repository.isBuiltIn(set.prefix)

    val downloadedSets: StateFlow<Map<String, DownloadedIconSet>> = downloads.sets

    /** Per-set download progress or failure for this session; absent means idle. */
    var downloadStates: Map<String, SetDownloadState> by mutableStateOf(emptyMap())
        private set
    var downloadOffer: SetDownloadOffer? by mutableStateOf(null)
        private set
    var pendingDelete: IconifyCollection? by mutableStateOf(null)
        private set
    var downloadedOnly by mutableStateOf(false)
    private val downloadJobs = mutableMapOf<String, Job>()

    /** Looks up the newest pinned release first, so the confirmation can show its real size. */
    fun offerDownload(set: IconifyCollection) {
        val state = downloadStates[set.prefix]
        if (state == SetDownloadState.Preparing || state is SetDownloadState.Downloading) return
        downloadStates = downloadStates + (set.prefix to SetDownloadState.Preparing)
        launchInSession { started ->
            val lookup = downloads.latestRelease(set.prefix)
            if (!isCurrent(started)) return@launchInSession
            when (lookup) {
                is IconSetReleaseLookup.Found -> {
                    downloadStates = downloadStates - set.prefix
                    downloadOffer = SetDownloadOffer(set, lookup.release, downloadedSets.value[set.prefix])
                }
                IconSetReleaseLookup.NotAvailable -> fail(set.prefix, SetDownloadFailure.NotAvailable)
                IconSetReleaseLookup.Failed -> fail(set.prefix, SetDownloadFailure.Network)
            }
        }
    }

    fun dismissDownloadOffer() {
        downloadOffer = null
    }

    fun confirmDownload() {
        val offer = downloadOffer ?: return
        downloadOffer = null
        val prefix = offer.set.prefix
        downloadStates = downloadStates + (prefix to SetDownloadState.Downloading(0f))
        var shownPercent = 0
        downloadJobs[prefix] = launchInSession { started ->
            val result = downloads.download(offer.release) { progress ->
                val percent = (progress * 100).toInt()
                if (percent == shownPercent) return@download
                shownPercent = percent
                viewModelScope.launch {
                    if (isCurrent(started) && downloadStates[prefix] is SetDownloadState.Downloading) {
                        downloadStates = downloadStates + (prefix to SetDownloadState.Downloading(percent / 100f))
                    }
                }
            }
            if (!isCurrent(started)) return@launchInSession
            downloadJobs.remove(prefix)
            when (result) {
                IconSetDownloadResult.Done -> {
                    downloadStates = downloadStates - prefix
                    selectedCollection?.takeIf { it.prefix == prefix }?.let(::loadIcons)
                }
                IconSetDownloadResult.NotAvailable -> fail(prefix, SetDownloadFailure.NotAvailable)
                IconSetDownloadResult.IntegrityFailed -> fail(prefix, SetDownloadFailure.Damaged)
                IconSetDownloadResult.Failed -> fail(prefix, SetDownloadFailure.Network)
            }
        } ?: return
    }

    fun cancelDownload(prefix: String) {
        downloadJobs.remove(prefix)?.cancel()
        downloadStates = downloadStates - prefix
    }

    private fun fail(prefix: String, reason: SetDownloadFailure) {
        downloadStates = downloadStates + (prefix to SetDownloadState.Failed(reason))
    }

    fun requestDelete(set: IconifyCollection) {
        pendingDelete = set
    }

    fun dismissDelete() {
        pendingDelete = null
    }

    /** Runs outside the session so closing the browser right after the tap still removes the files. */
    fun confirmDelete() {
        val set = pendingDelete ?: return
        pendingDelete = null
        viewModelScope.launch {
            downloads.delete(set.prefix)
            selectedCollection?.takeIf { it.prefix == set.prefix }?.let(::loadIcons)
        }
    }

    // Each open session owns its loads: closing cancels them, and a late result never publishes
    // into the next session, even when the browser reopens at once (the generation moves on).
    private var sessionJob: Job? = null
    private var generation = 0L
    private var iconsJob: Job? = null

    private fun launchInSession(block: suspend CoroutineScope.(started: Long) -> Unit): Job? {
        val session = sessionJob ?: return null
        val started = generation
        return CoroutineScope(viewModelScope.coroutineContext + session).launch { block(started) }
    }

    private fun isCurrent(started: Long) = sessionJob?.isActive == true && generation == started

    private val parsedSvgCache = LruCache<String, SvgVectorImporter.ImportedSvg>(PARSED_SVG_COUNT)

    // Rasterised previews for icons the strict importer rejects (gradient/clip documents).
    // Keyed by source revision + URL + tint because currentColor is substituted at render time.
    private val rasterCache = object : LruCache<String, ImageBitmap>(RASTER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    init {
        viewModelScope.launch {
            downloads.sets.collect { current ->
                val previous = previewSources
                previewSources = current
                selectedCollection?.takeIf { previous[it.prefix] != current[it.prefix] }?.let(::loadIcons)
            }
        }
    }

    fun beginSession() {
        if (sessionJob?.isActive == true) return
        generation++
        sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        resetSessionState()
        viewModelScope.launch { downloads.ensureScanned() }
        loadCollections()
    }

    fun endSession() {
        sessionJob?.cancel()
        sessionJob = null
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
        iconsJob?.cancel()
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
        val key = repository.previewCacheKey(icon)
        memoryPreview(key, tintArgb, rasterSize)?.let { return it }
        val markup = repository.svg(icon) ?: return null
        if (key != repository.previewCacheKey(icon)) return null
        val preview = renderPreview(icon, key, markup, tintArgb, rasterSize)
        return preview.takeIf { key == repository.previewCacheKey(icon) }
    }

    internal suspend fun cachedPreview(
        icon: OnlineIcon,
        tintArgb: Int,
        detail: Boolean
    ): OnlineIconCachedPreview {
        val rasterSize = if (detail) DETAIL_RASTER_SIZE else PREVIEW_RASTER_SIZE
        val key = repository.previewCacheKey(icon)
        memoryPreview(key, tintArgb, rasterSize)?.let { return OnlineIconCachedPreview.Ready(it) }
        val markup = repository.svg(icon, cachedOnly = true) ?: return OnlineIconCachedPreview.Missing
        if (key != repository.previewCacheKey(icon)) return OnlineIconCachedPreview.Missing
        val rendered = renderPreview(icon, key, markup, tintArgb, rasterSize)
        if (key != repository.previewCacheKey(icon)) return OnlineIconCachedPreview.Missing
        return rendered
            ?.let { OnlineIconCachedPreview.Ready(it) } ?: OnlineIconCachedPreview.Failed
    }

    private fun memoryPreview(key: String, tintArgb: Int, rasterSize: Int): OnlineIconPreview? {
        parsedSvgCache.get(key)?.let { return OnlineIconPreview.Editable(it) }
        val rasterKey = "$key#$tintArgb#$rasterSize"
        return rasterCache.get(rasterKey)?.let { OnlineIconPreview.PreviewOnly(it) }
    }

    private suspend fun renderPreview(
        icon: OnlineIcon,
        key: String,
        markup: String,
        tintArgb: Int,
        rasterSize: Int
    ): OnlineIconPreview? {
        val rasterKey = "$key#$tintArgb#$rasterSize"
        val preview = withContext(Dispatchers.Default) {
            SvgVectorImporter.parse(markup)?.let { parsed ->
                parsedSvgCache.put(key, parsed)
                OnlineIconPreview.Editable(parsed)
            } ?: SvgRasterizer.rasterize(markup, rasterSize, tintArgb)
                ?.asImageBitmap()
                ?.let { bitmap ->
                    rasterCache.put(rasterKey, bitmap)
                    OnlineIconPreview.PreviewOnly(bitmap)
                }
        }
        if (preview == null) {
            Log.error("OnlineIcons", "SVG could not be parsed or rasterized: ${icon.svgUrl}")
        }
        return preview
    }

    /** The close-up preview for the detail dialog — rasters render at [DETAIL_RASTER_SIZE]. */
    suspend fun detailPreview(icon: OnlineIcon, tintArgb: Int): OnlineIconPreview? =
        preview(icon, tintArgb, DETAIL_RASTER_SIZE)

    /**
     * Full-size raster for "use as image": the icon exactly as its SVG paints it, at the
     * same resolution the upload gallery imports at — and saved into the gallery so it can
     * be reused like any uploaded picture. A failed disk write still imports the bitmap
     * (galleryPath null); only fetch/render failures return null.
     */
    suspend fun importImage(icon: OnlineIcon, currentColorArgb: Int): OnlineImageImport? {
        val key = repository.previewCacheKey(icon)
        val markup = repository.svg(icon) ?: return null
        if (key != repository.previewCacheKey(icon)) return null
        val bitmap = withContext(Dispatchers.Default) {
            SvgRasterizer.rasterize(markup, IMAGE_IMPORT_SIZE, currentColorArgb)
        } ?: return null
        if (key != repository.previewCacheKey(icon)) return null
        val saved = withContext(Dispatchers.IO) {
            runCatchingCancellable { UploadedImageStore.save(context, bitmap) }.getOrNull()
        }
        return OnlineImageImport(bitmap, saved?.absolutePath)
    }

    /** Import resolution for a tapped icon: distinguishes "couldn't fetch" from "fetched,
     * but not modellable as editable vector paths" so the UI can explain which happened. */
    suspend fun importIcon(icon: OnlineIcon): OnlineIconImport {
        val key = repository.previewCacheKey(icon)
        parsedSvgCache.get(key)?.let { return OnlineIconImport.Imported(it) }
        val markup = repository.svg(icon) ?: return OnlineIconImport.LoadFailed
        if (key != repository.previewCacheKey(icon)) return OnlineIconImport.LoadFailed
        val parsed = withContext(Dispatchers.Default) { SvgVectorImporter.parse(markup) }
            ?: return OnlineIconImport.NotImportable
        if (key != repository.previewCacheKey(icon)) return OnlineIconImport.LoadFailed
        parsedSvgCache.put(key, parsed)
        return OnlineIconImport.Imported(parsed)
    }

    private fun loadCollections() {
        collections = null
        collectionsFailed = false
        launchInSession { started ->
            val loaded = repository.collections()
            if (!isCurrent(started)) return@launchInSession
            collections = loaded
            collectionsFailed = loaded == null
        }
    }

    private fun loadIcons(collection: IconifyCollection) {
        icons = null
        iconsFailed = false
        iconsJob?.cancel()
        iconsJob = launchInSession { started ->
            val loaded = repository.icons(collection)
            if (!isCurrent(started) || selectedCollection?.prefix != collection.prefix) return@launchInSession
            icons = loaded
            iconsFailed = loaded == null
        }
    }

    private fun resetSessionState() {
        iconsJob?.cancel()
        iconsJob = null
        downloadJobs.clear()
        downloadStates = emptyMap()
        downloadOffer = null
        pendingDelete = null
        downloadedOnly = false
        selectedCollection = null
        collections = null
        collectionsFailed = false
        icons = null
        iconsFailed = false
        collectionQuery = ""
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
