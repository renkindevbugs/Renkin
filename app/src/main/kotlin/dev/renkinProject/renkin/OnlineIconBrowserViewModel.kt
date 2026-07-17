package dev.renkinProject.renkin

import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.renkinProject.renkin.data.online.IconifyCollection
import dev.renkinProject.renkin.data.online.OnlineIcon
import dev.renkinProject.renkin.data.online.OnlineIconRepository
import dev.renkinProject.renkin.vector.SvgVectorImporter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    suspend fun importedSvg(icon: OnlineIcon): SvgVectorImporter.ImportedSvg? {
        parsedSvgCache.get(icon.svgUrl)?.let { return it }
        val parsed = repository.svg(icon)?.let { markup ->
            withContext(Dispatchers.Default) { SvgVectorImporter.parse(markup) }
        }
        if (parsed != null) parsedSvgCache.put(icon.svgUrl, parsed)
        return parsed
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
        const val MIN_SEARCH_LENGTH = 2
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}
