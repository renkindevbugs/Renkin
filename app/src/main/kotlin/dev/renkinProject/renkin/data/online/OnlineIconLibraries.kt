package dev.renkinProject.renkin.data.online

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import android.util.LruCache
import android.util.Xml
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.renkinProject.renkin.BuildConfig
import dev.renkinProject.renkin.extension.toLowerHex
import dev.renkinProject.renkin.util.Log
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/**
 * Online icons come from the Iconify API (api.iconify.design) — an open-source aggregator of
 * 200+ FOSS icon sets with unified licence metadata, categories and a colour/monochrome flag.
 * Only the set list, one set's icon-name index and the individually viewed SVGs are ever
 * downloaded — never a whole set.
 *
 * Note this deliberately lists SETS OF SVG FILES, not Android icon packs — packs install
 * normally and go through the pack browser.
 */
data class IconifyCollection(
    val prefix: String,
    val name: String,
    val total: Int,
    val license: String,
    val category: String,
    val palette: Boolean,
    val samples: List<String>
)

data class OnlineIcon(val prefix: String, val name: String) {
    val label: String get() = name.replace('-', ' ')

    val svgUrl: String get() = "https://api.iconify.design/$prefix/$name.svg"
}

/**
 * Human label for a stored attribution URL: the Iconify set prefix for API URLs, and the
 * "owner/repo" of the GitHub CDN URLs an earlier build of this feature stored.
 */
fun onlineAttributionLabel(url: String): String? {
    Regex("api\\.iconify\\.design/([^/]+)/").find(url)?.let { return it.groupValues[1] }
    val gh = Regex("/gh/([^/@]+)/([^/@]+)@").find(url) ?: return null
    val (owner, repo) = gh.destructured
    return "$owner/$repo"
}

/**
 * Fetches the Iconify set list, per-set icon indexes and individual SVGs, with a disk cache
 * under cacheDir so browsing works offline once visited. Every method returns null on failure
 * instead of throwing — the browser shows a retryable error state.
 */
@Singleton
class OnlineIconRepository internal constructor(
    private val context: Context,
    private val request: suspend (String, Int) -> HttpTextResponse,
    private val monotonicMillis: () -> Long,
    private val ioDispatcher: CoroutineDispatcher
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context, ::executeHttpGet, SystemClock::elapsedRealtime, Dispatchers.IO
    )

    @Volatile private var memoryCollections: List<IconifyCollection>? = null
    private val memoryIndexes = ConcurrentHashMap<String, List<OnlineIcon>>()
    private val indexLocks = ConcurrentHashMap<String, Mutex>()
    private val svgLocks = ConcurrentHashMap<String, Mutex>()
    private val svgFetchPermits = Semaphore(SVG_FETCH_CONCURRENCY)
    private val requestTimingMutex = Mutex()
    private var nextRequestAt = 0L
    private val mutableRateLimitUntil = MutableStateFlow(0L)
    val rateLimitUntil = mutableRateLimitUntil.asStateFlow()
    private val svgMemory = object : LruCache<String, String>(SVG_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: String): Int = value.toByteArray(Charsets.UTF_8).size
    }
    private val searchMemory = LruCache<String, List<OnlineIcon>>(SEARCH_MEMORY_QUERIES)
    @Volatile private var preferredApiHost = API_HOSTS.first()

    private val cacheRoot: File
        get() = File(context.cacheDir, "online-icons").apply { mkdirs() }

    suspend fun collections(): List<IconifyCollection>? = withContext(ioDispatcher) {
        memoryCollections?.let { return@withContext it }
        indexLocks.getOrPut("collections") { Mutex() }.withLock {
            memoryCollections ?: cachedOrFetch(
                cache = File(cacheRoot, "iconify-collections.json"),
                url = "https://api.iconify.design/collections",
                parses = { parseCollections(it) != null }
            )?.let(::parseCollections)?.also { memoryCollections = it }
        }
    }

    suspend fun icons(collection: IconifyCollection): List<OnlineIcon>? = withContext(ioDispatcher) {
        memoryIndexes[collection.prefix]?.let { return@withContext it }
        indexLocks.getOrPut("set:${collection.prefix}") { Mutex() }.withLock {
            memoryIndexes[collection.prefix] ?: cachedOrFetch(
                cache = File(cacheRoot, "iconify-set-${cacheKey(collection.prefix)}.json"),
                url = "https://api.iconify.design/collection?prefix=" +
                    URLEncoder.encode(collection.prefix, "UTF-8"),
                parses = { parseCollection(it) != null }
            )?.let(::parseCollection)?.also { memoryIndexes[collection.prefix] = it }
        }
    }

    /**
     * Cross-set icon search through the API — matches icon NAMES across every set at once,
     * like the search box on iconify.design. Memory-cached per query for the session; an
     * empty list is a valid "no matches", null means the request failed.
     */
    suspend fun search(query: String): List<OnlineIcon>? = withContext(ioDispatcher) {
        val key = query.trim().lowercase()
        searchMemory.get(key)?.let { return@withContext it }
        val json = httpGetText(
            "https://api.iconify.design/search?query=" +
                URLEncoder.encode(key, "UTF-8") + "&limit=$SEARCH_LIMIT",
            MAX_INDEX_BYTES
        ) ?: return@withContext null
        parseSearch(json)?.also { searchMemory.put(key, it) }
    }

    suspend fun svg(icon: OnlineIcon, cachedOnly: Boolean = false): String? = withContext(ioDispatcher) {
        svgMemory.get(icon.svgUrl)?.let { return@withContext it }
        if (cachedOnly) {
            return@withContext readCachedSvg(icon)?.also { svgMemory.put(icon.svgUrl, it) }
        }
        val lock = svgLocks.getOrPut(icon.svgUrl) { Mutex() }
        try {
            lock.withLock {
                svgMemory.get(icon.svgUrl)?.let { return@withLock it }
                loadSvg(icon)?.also { svgMemory.put(icon.svgUrl, it) }
            }
        } finally {
            if (!lock.isLocked) svgLocks.remove(icon.svgUrl, lock)
        }
    }

    private fun svgCacheFile(icon: OnlineIcon): File {
        val dir = File(cacheRoot, "iconify-${cacheKey(icon.prefix)}").apply { mkdirs() }
        return File(dir, "${cacheKey(icon.svgUrl)}.svg")
    }

    private fun readCachedSvg(icon: OnlineIcon): String? = svgCacheFile(icon)
        .takeIf { it.isFile }
        ?.let { runCatching { it.readText() }.getOrNull() }
        ?.takeIf(::isWellFormedSvg)

    private suspend fun loadSvg(icon: OnlineIcon): String? {
        readCachedSvg(icon)?.let { return it }
        val markup = httpGetText(icon.svgUrl, MAX_SVG_BYTES, limitSvgRequests = true) ?: return null
        if (!isWellFormedSvg(markup)) {
            Log.error("OnlineIcons", "Invalid SVG response: ${icon.svgUrl}")
            return null
        }
        writeAtomically(svgCacheFile(icon), markup)
        return markup
    }

    /**
     * Shared cache policy: serve a fresh fetch when the cache is missing or older than
     * [INDEX_TTL_MS], otherwise the cached copy; a fetch that fails [parses] must never
     * clobber a still-parsable cache.
     */
    private suspend fun cachedOrFetch(
        cache: File,
        url: String,
        parses: (String) -> Boolean
    ): String? {
        val cached = cache.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
            ?.takeIf(parses)
        val fresh = if (cached == null || cache.ageMs() > INDEX_TTL_MS) {
            httpGetText(url, MAX_INDEX_BYTES)
        } else null
        if (fresh != null && parses(fresh)) {
            writeAtomically(cache, fresh)
            return fresh
        }
        return cached
    }

    private fun writeAtomically(file: File, content: String) {
        val atomic = AtomicFile(file)
        var output: java.io.FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (e: IOException) {
            output?.let(atomic::failWrite)
            Log.error("OnlineIcons", "Could not update cache: ${file.name}", e)
        }
    }

    private fun File.ageMs(): Long = System.currentTimeMillis() - lastModified()

    private suspend fun httpGetText(
        url: String,
        maxBytes: Int,
        limitSvgRequests: Boolean = false
    ): String? {
        require(url.startsWith(API_HOSTS.first() + "/")) { "Expected a canonical Iconify API URL" }
        var host = preferredApiHost
        repeat(HTTP_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            val requestUrl = host + url.removePrefix(API_HOSTS.first())
            val response = try {
                executeThrottledGet(requestUrl, maxBytes, limitSvgRequests)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                Log.error("OnlineIcons", "GET failed: $requestUrl", e)
                HttpTextResponse(retryable = true)
            }
            response.body?.let {
                preferredApiHost = host
                return it
            }
            if (!response.retryable || attempt == HTTP_ATTEMPTS - 1) {
                Log.error("OnlineIcons", "GET unavailable: $requestUrl (HTTP ${response.status ?: "unknown"})")
                return null
            }

            // A rate limit applies to this request; do not evade it by changing hosts.
            if (response.status == 429) return@repeat
            host = API_HOSTS[(API_HOSTS.indexOf(host) + 1) % API_HOSTS.size]
            val exponentialDelay = HTTP_RETRY_BASE_DELAY_MS * (1L shl attempt)
            delay(response.retryAfterMs ?: exponentialDelay)
        }
        return null
    }

    private suspend fun executeThrottledGet(
        url: String,
        maxBytes: Int,
        limitSvgRequests: Boolean
    ): HttpTextResponse {
        while (true) {
            val waitMs = requestTimingMutex.withLock {
                (maxOf(nextRequestAt, mutableRateLimitUntil.value) - monotonicMillis()).coerceAtLeast(0L)
            }
            if (waitMs > 0) {
                delay(waitMs)
                continue
            }
            if (limitSvgRequests) svgFetchPermits.acquire()
            try {
                // Recheck after acquiring a slot: another response may have imposed a shared pause.
                val admitted = requestTimingMutex.withLock {
                    val now = monotonicMillis()
                    if (now < maxOf(nextRequestAt, mutableRateLimitUntil.value)) false else {
                        nextRequestAt = now + HTTP_REQUEST_INTERVAL_MS
                        true
                    }
                }
                if (!admitted) continue
                val response = request(url, maxBytes)
                if (response.status == 429) {
                    val pauseMs = (response.retryAfterMs ?: HTTP_RATE_LIMIT_FALLBACK_MS)
                        .coerceIn(HTTP_REQUEST_INTERVAL_MS, HTTP_MAX_RATE_LIMIT_MS)
                    requestTimingMutex.withLock {
                        mutableRateLimitUntil.value = maxOf(
                            mutableRateLimitUntil.value,
                            monotonicMillis() + pauseMs
                        )
                    }
                    if (BuildConfig.DEBUG) {
                        Log.debug("OnlineIcons", "API rate limited; sharedPauseMs=$pauseMs")
                    }
                }
                return response
            } finally {
                if (limitSvgRequests) svgFetchPermits.release()
            }
        }
    }

    companion object {
        private val API_HOSTS = listOf(
            "https://api.iconify.design",
            "https://api.simplesvg.com",
            "https://api.unisvg.com"
        )
        private const val INDEX_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_INDEX_BYTES = 8 * 1024 * 1024
        private const val MAX_SVG_BYTES = 512 * 1024
        private const val SVG_MEMORY_BYTES = 2 * 1024 * 1024
        private const val SVG_FETCH_CONCURRENCY = 6
        private const val SEARCH_LIMIT = 120
        private const val SEARCH_MEMORY_QUERIES = 24
        private const val HTTP_ATTEMPTS = 3
        private const val HTTP_RETRY_BASE_DELAY_MS = 400L
        private const val HTTP_MAX_RETRY_AFTER_MS = 15_000L
        private const val HTTP_REQUEST_INTERVAL_MS = 300L
        private const val HTTP_RATE_LIMIT_FALLBACK_MS = 60_000L
        private const val HTTP_MAX_RATE_LIMIT_MS = 5 * 60_000L

        fun parseSearch(json: String): List<OnlineIcon>? = runCatching {
            val array = JSONObject(json).optJSONArray("icons") ?: return null
            val icons = mutableListOf<OnlineIcon>()
            for (i in 0 until array.length()) {
                val full = array.optString(i)
                val split = full.indexOf(':')
                if (split <= 0 || split >= full.length - 1) continue
                icons.add(OnlineIcon(full.substring(0, split), full.substring(split + 1)))
            }
            icons.toList()
        }.getOrNull()

        internal fun isWellFormedSvg(markup: String): Boolean = runCatching {
            val parser = Xml.newPullParser().apply { setInput(StringReader(markup)) }
            var svgDepth: Int? = null
            var closedSvg = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "svg" && svgDepth == null) {
                    svgDepth = parser.depth
                } else if (parser.eventType == XmlPullParser.END_TAG &&
                    parser.name == "svg" && parser.depth == svgDepth
                ) {
                    closedSvg = true
                }
                parser.next()
            }
            svgDepth != null && closedSvg
        }.getOrDefault(false)

        private fun cacheKey(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .toLowerHex()

        fun parseCollections(json: String): List<IconifyCollection>? = runCatching {
            val root = JSONObject(json)
            val collections = mutableListOf<IconifyCollection>()
            for (prefix in root.keys()) {
                val entry = root.optJSONObject(prefix) ?: continue
                val name = entry.optString("name")
                val total = entry.optInt("total")
                if (name.isEmpty() || total <= 0) continue
                val license = entry.optJSONObject("license")?.let { license ->
                    license.optString("spdx").ifEmpty { license.optString("title") }
                }.orEmpty()
                val samples = entry.optJSONArray("samples")?.let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        array.optString(i).takeIf { it.isNotEmpty() }
                    }
                }.orEmpty()
                collections.add(
                    IconifyCollection(
                        prefix = prefix,
                        name = name,
                        total = total,
                        license = license,
                        category = entry.optString("category"),
                        palette = entry.optBoolean("palette"),
                        samples = samples.take(3)
                    )
                )
            }
            collections.sortBy { it.name.lowercase() }
            collections.toList().takeIf { it.isNotEmpty() }
        }.getOrNull()

        fun parseCollection(json: String): List<OnlineIcon>? = runCatching {
            val root = JSONObject(json)
            val prefix = root.optString("prefix").takeIf { it.isNotEmpty() } ?: return null
            val names = linkedSetOf<String>()
            root.optJSONArray("uncategorized")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf { it.isNotEmpty() }?.let(names::add)
                }
            }
            root.optJSONObject("categories")?.let { categories ->
                for (category in categories.keys()) {
                    val array = categories.optJSONArray(category) ?: continue
                    for (i in 0 until array.length()) {
                        array.optString(i).takeIf { it.isNotEmpty() }?.let(names::add)
                    }
                }
            }
            names.map { OnlineIcon(prefix, it) }
                .sortedBy { it.name }
                .takeIf { it.isNotEmpty() }
        }.getOrNull()

        private suspend fun executeHttpGet(url: String, maxBytes: Int): HttpTextResponse =
            withContext(Dispatchers.IO) {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3_000
                    readTimeout = 5_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Renkin (Android)")
                    setRequestProperty("Accept-Encoding", "identity")
                }
                // Disconnect while the blocking child is still running when its caller is cancelled.
                coroutineScope {
                    val request = async {
                        runInterruptible { readHttpResponse(connection, maxBytes) }
                    }
                    try {
                        request.await()
                    } finally {
                        connection.disconnect()
                    }
                }
            }

        private fun readHttpResponse(connection: HttpURLConnection, maxBytes: Int): HttpTextResponse {
            val startedAt = System.nanoTime()
            val status = connection.responseCode
            if (BuildConfig.DEBUG) {
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                Log.debug("OnlineIcons", "GET HTTP $status headersMs=$elapsedMs ${connection.url}")
            }
            if (status != HttpURLConnection.HTTP_OK) {
                return HttpTextResponse(
                    status = status,
                    retryable = status in TRANSIENT_HTTP_STATUSES,
                    retryAfterMs = if (status == 429 || status == 503) {
                        retryAfterDelayMs(
                            connection.getHeaderField("Retry-After"),
                            maxDelayMs = if (status == 429) HTTP_MAX_RATE_LIMIT_MS else HTTP_MAX_RETRY_AFTER_MS
                        )
                    } else {
                        null
                    }
                )
            }
            val body = connection.inputStream.use { stream ->
                val bytes = stream.readNBytesCompat(maxBytes + 1)
                if (bytes.size > maxBytes) null else String(bytes, Charsets.UTF_8)
            }
            if (BuildConfig.DEBUG) {
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                Log.debug("OnlineIcons", "GET complete HTTP $status totalMs=$elapsedMs ${connection.url}")
            }
            return HttpTextResponse(body = body, status = status)
        }

        internal fun retryAfterDelayMs(
            value: String?,
            nowMillis: Long = System.currentTimeMillis(),
            maxDelayMs: Long = HTTP_MAX_RETRY_AFTER_MS
        ): Long? {
            val raw = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
            val delayMs = raw.toLongOrNull()?.let { seconds ->
                seconds.coerceIn(0L, maxDelayMs / 1_000L) * 1_000L
            }
                ?: runCatching {
                    ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant()
                        .toEpochMilli() - nowMillis
                }.getOrNull()
            return delayMs?.coerceIn(0L, maxDelayMs)
        }

        /** InputStream.readNBytes needs API 33 on Android; minSdk is lower. */
        private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (total <= limit) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("GET cancelled")
                val read = read(chunk, 0, minOf(chunk.size, limit - total + 1))
                if (read < 0) break
                buffer.write(chunk, 0, read)
                total += read
            }
            return buffer.toByteArray()
        }

        internal data class HttpTextResponse(
            val body: String? = null,
            val status: Int? = null,
            val retryable: Boolean = false,
            val retryAfterMs: Long? = null
        )

        private val TRANSIENT_HTTP_STATUSES = setOf(408, 425, 429, 500, 502, 503, 504)
    }
}
