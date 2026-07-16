package dev.renkinProject.renkin.data.online

import android.content.Context
import dev.renkinProject.renkin.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A curated FOSS SVG icon library browsable from the vector editor. Only the file index and
 * the individually picked SVGs are ever downloaded — never the whole set — via the jsDelivr
 * CDN (no GitHub API rate limits, aggressively cached).
 *
 * Curation rule: only sets WITHOUT an installable Android icon pack belong here. Sets that
 * ship one (Arcticons, Lawnicons, …) go through the normal installed-pack flow instead.
 */
data class OnlineIconLibrary(
    val id: String,
    val label: String,
    /** SPDX-ish licence label shown in the browser. */
    val license: String,
    val owner: String,
    val repo: String,
    /** Repo subfolder holding the SVGs (with trailing slash). */
    val pathPrefix: String,
    val projectUrl: String
)

val OnlineIconLibraries: List<OnlineIconLibrary> = listOf(
    OnlineIconLibrary(
        id = "simple-icons",
        label = "Simple Icons",
        license = "CC0-1.0",
        owner = "simple-icons",
        repo = "simple-icons",
        pathPrefix = "icons/",
        projectUrl = "https://github.com/simple-icons/simple-icons"
    ),
    OnlineIconLibrary(
        id = "tabler-icons",
        label = "Tabler Icons",
        license = "MIT",
        owner = "tabler",
        repo = "tabler-icons",
        pathPrefix = "icons/outline/",
        projectUrl = "https://github.com/tabler/tabler-icons"
    )
)

/** One icon in a library's index; the SVG itself is fetched only when needed. */
data class OnlineIcon(
    val library: OnlineIconLibrary,
    val slug: String,
    /** The resolved repo version the index was read from — pins the CDN URL. */
    val version: String
) {
    val label: String get() = slug.replace('-', ' ')

    /** Public, versioned URL of the SVG — also stored as the icon's attribution reference. */
    val svgUrl: String
        get() = "https://cdn.jsdelivr.net/gh/${library.owner}/${library.repo}@$version/${library.pathPrefix}$slug.svg"
}

/**
 * Fetches library indexes (one jsDelivr file listing per library) and individual SVGs, with
 * a disk cache under cacheDir so browsing works offline once visited. Every method returns
 * null on failure instead of throwing — the browser shows a retryable error state.
 */
class OnlineIconRepository(private val context: Context) {

    private val memoryIndexes = mutableMapOf<String, List<OnlineIcon>>()

    private val cacheRoot: File
        get() = File(context.cacheDir, "online-icons").apply { mkdirs() }

    /** The library's icon index, newest cached copy first (refreshed after [INDEX_TTL_MS]). */
    suspend fun icons(library: OnlineIconLibrary): List<OnlineIcon>? = withContext(Dispatchers.IO) {
        memoryIndexes[library.id]?.let { return@withContext it }
        val cache = File(cacheRoot, "${library.id}-index.json")
        val cached = cache.takeIf { it.isFile }?.let { file ->
            runCatching { file.readText() }.getOrNull()
        }
        val fresh = if (cached == null || cache.ageMs() > INDEX_TTL_MS) fetchIndexJson(library) else null
        val json = fresh ?: cached ?: return@withContext null
        val parsed = parseIndex(json, library)
        if (parsed.isNullOrEmpty()) {
            // A fetch that yielded garbage must not clobber a still-parsable cache.
            return@withContext cached?.let { parseIndex(it, library) }?.takeIf { it.isNotEmpty() }
                ?.also { memoryIndexes[library.id] = it }
        }
        if (fresh != null) runCatching { cache.writeText(fresh) }
        memoryIndexes[library.id] = parsed
        parsed
    }

    /** The icon's SVG markup, from the per-icon disk cache or the CDN. */
    suspend fun svg(icon: OnlineIcon): String? = withContext(Dispatchers.IO) {
        val dir = File(cacheRoot, icon.library.id).apply { mkdirs() }
        // Slugs are filenames from the repo listing, but never trust them as paths.
        val safeName = icon.slug.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val cache = File(dir, "$safeName.svg")
        if (cache.isFile) {
            runCatching { cache.readText() }.getOrNull()?.let { return@withContext it }
        }
        val markup = httpGetText(icon.svgUrl, MAX_SVG_BYTES) ?: return@withContext null
        runCatching { cache.writeText(markup) }
        markup
    }

    /**
     * Downloads the index: resolves the repo's latest tagged version, then lists its files —
     * two small requests total. Stored as one JSON with the version embedded.
     */
    private fun fetchIndexJson(library: OnlineIconLibrary): String? {
        val resolved = httpGetText(
            "https://data.jsdelivr.com/v1/packages/gh/${library.owner}/${library.repo}/resolved",
            MAX_INDEX_BYTES
        ) ?: return null
        val version = runCatching { JSONObject(resolved).optString("version") }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val listing = httpGetText(
            "https://data.jsdelivr.com/v1/packages/gh/${library.owner}/${library.repo}@$version?structure=flat",
            MAX_INDEX_BYTES
        ) ?: return null
        // Re-wrap so the cache file carries the version the listing belongs to.
        return runCatching {
            JSONObject().put("version", version)
                .put("files", JSONObject(listing).getJSONArray("files"))
                .toString()
        }.getOrNull()
    }

    private fun File.ageMs(): Long = System.currentTimeMillis() - lastModified()

    companion object {
        private const val INDEX_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_INDEX_BYTES = 8 * 1024 * 1024
        private const val MAX_SVG_BYTES = 512 * 1024

        /**
         * Parses a cached index ({"version", "files":[{"name":"/icons/x.svg"}…]}) into icons:
         * .svg files directly under the library's path prefix, sorted by slug. Null when the
         * JSON is unreadable.
         */
        fun parseIndex(json: String, library: OnlineIconLibrary): List<OnlineIcon>? = runCatching {
            val root = JSONObject(json)
            val version = root.getString("version")
            val files = root.getJSONArray("files")
            val prefix = "/${library.pathPrefix}"
            val icons = mutableListOf<OnlineIcon>()
            for (i in 0 until files.length()) {
                val name = files.getJSONObject(i).optString("name")
                if (!name.startsWith(prefix) || !name.endsWith(".svg")) continue
                val slug = name.removePrefix(prefix).removeSuffix(".svg")
                if (slug.isEmpty() || '/' in slug) continue
                icons.add(OnlineIcon(library, slug, version))
            }
            icons.sortBy { it.slug }
            icons.toList()
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
