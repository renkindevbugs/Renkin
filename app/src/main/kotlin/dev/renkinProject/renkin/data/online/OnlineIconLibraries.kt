package dev.renkinProject.renkin.data.online

import android.content.Context
import android.util.AtomicFile
import android.util.LruCache
import android.util.Xml
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.renkinProject.renkin.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    /** Iconify's set id, e.g. "mdi" or "simple-icons". */
    val prefix: String,
    val name: String,
    val total: Int,
    /** SPDX licence id (falls back to the licence title); empty when the API lists none. */
    val license: String,
    /** Iconify category ("General", "Brands / Social", …); empty when uncategorised. */
    val category: String,
    /** True = a multicolour set (emoji, flags, flat-colour icons); false = monochrome glyphs. */
    val palette: Boolean,
    /** A few icon names the API suggests as previews. */
    val samples: List<String>
)

/** One icon in a set; the SVG itself is fetched only when needed. */
data class OnlineIcon(val prefix: String, val name: String) {
    val label: String get() = name.replace('-', ' ')

    /** Public URL of the SVG — also stored as the icon's attribution reference. */
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
class OnlineIconRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    @Volatile private var memoryCollections: List<IconifyCollection>? = null
    private val memoryIndexes = ConcurrentHashMap<String, List<OnlineIcon>>()
    private val indexLocks = ConcurrentHashMap<String, Mutex>()
    private val svgLocks = ConcurrentHashMap<String, Mutex>()
    private val svgFetchPermits = Semaphore(SVG_FETCH_CONCURRENCY)
    private val svgMemory = object : LruCache<String, String>(SVG_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: String): Int = value.toByteArray(Charsets.UTF_8).size
    }

    private val cacheRoot: File
        get() = File(context.cacheDir, "online-icons").apply { mkdirs() }

    /** Every Iconify set with its metadata (licence, category, palette), cached for a week. */
    suspend fun collections(): List<IconifyCollection>? = withContext(Dispatchers.IO) {
        memoryCollections?.let { return@withContext it }
        indexLocks.getOrPut("collections") { Mutex() }.withLock {
            memoryCollections ?: cachedOrFetch(
                cache = File(cacheRoot, "iconify-collections.json"),
                url = "https://api.iconify.design/collections",
                parses = { parseCollections(it) != null }
            )?.let(::parseCollections)?.also { memoryCollections = it }
        }
    }

    /** One set's icon names, cached for a week. */
    suspend fun icons(collection: IconifyCollection): List<OnlineIcon>? = withContext(Dispatchers.IO) {
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

    /** The icon's SVG markup, from the per-icon disk cache or the API. */
    suspend fun svg(icon: OnlineIcon): String? = withContext(Dispatchers.IO) {
        svgMemory.get(icon.svgUrl)?.let { return@withContext it }
        val lock = svgLocks.getOrPut(icon.svgUrl) { Mutex() }
        try {
            lock.withLock {
                svgMemory.get(icon.svgUrl)?.let { return@withLock it }
                svgFetchPermits.withPermit {
                    loadSvg(icon)?.also { svgMemory.put(icon.svgUrl, it) }
                }
            }
        } finally {
            if (!lock.isLocked) svgLocks.remove(icon.svgUrl, lock)
        }
    }

    private fun loadSvg(icon: OnlineIcon): String? {
        val dir = File(cacheRoot, "iconify-${cacheKey(icon.prefix)}").apply { mkdirs() }
        val cache = File(dir, "${cacheKey(icon.svgUrl)}.svg")
        if (cache.isFile) {
            runCatching { cache.readText() }.getOrNull()?.takeIf(::isWellFormedSvg)?.let { return it }
            cache.delete()
        }
        val markup = httpGetText(icon.svgUrl, MAX_SVG_BYTES)
            ?.takeIf(::isWellFormedSvg) ?: return null
        writeAtomically(cache, markup)
        return markup
    }

    /**
     * Shared cache policy: serve a fresh fetch when the cache is missing or older than
     * [INDEX_TTL_MS], otherwise the cached copy; a fetch that fails [parses] must never
     * clobber a still-parsable cache.
     */
    private fun cachedOrFetch(cache: File, url: String, parses: (String) -> Boolean): String? {
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

    companion object {
        private const val INDEX_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_INDEX_BYTES = 8 * 1024 * 1024
        private const val MAX_SVG_BYTES = 512 * 1024
        private const val SVG_MEMORY_BYTES = 2 * 1024 * 1024
        private const val SVG_FETCH_CONCURRENCY = 6

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
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        /**
         * Parses the /collections response ({prefix: {name, total, license, category,
         * palette, samples}, …}) into sets sorted by name. Null when unreadable.
         */
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

        /**
         * Parses one /collection response into its icon names: "uncategorized" plus every
         * category list, deduplicated and sorted. Null when unreadable or empty.
         */
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

        /** Small GET returning the body as text; null on any failure or oversized response. */
        private fun httpGetText(url: String, maxBytes: Int): String? {
            var connection: HttpURLConnection? = null
            return try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Renkin (Android)")
                    setRequestProperty("Accept-Encoding", "identity")
                }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                connection.inputStream.use { stream ->
                    val bytes = stream.readNBytesCompat(maxBytes + 1)
                    if (bytes.size > maxBytes) null else String(bytes, Charsets.UTF_8)
                }
            } catch (e: IOException) {
                Log.error("OnlineIcons", "GET failed: $url", e)
                null
            } finally {
                connection?.disconnect()
            }
        }

        /** InputStream.readNBytes needs API 33 on Android; minSdk is lower. */
        private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (total <= limit) {
                val read = read(chunk, 0, minOf(chunk.size, limit - total + 1))
                if (read < 0) break
                buffer.write(chunk, 0, read)
                total += read
            }
            return buffer.toByteArray()
        }
    }
}
