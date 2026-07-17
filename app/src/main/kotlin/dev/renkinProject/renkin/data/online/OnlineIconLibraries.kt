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

/**
 * Human label for a stored attribution URL: the curated library's name when it is one of
 * ours, otherwise the plain "owner/repo" of the community repository it points into.
 */
fun onlineAttributionLabel(url: String): String? {
    val match = Regex("/gh/([^/@]+)/([^/@]+)@").find(url) ?: return null
    val (owner, repo) = match.destructured
    return OnlineIconLibraries.firstOrNull { it.owner == owner && it.repo == repo }?.label
        ?: "$owner/$repo"
}

/**
 * A community repository discovered through GitHub's `icon-pack` topic — browsable exactly
 * like a curated library (empty path prefix: every SVG in the repo, wherever it sits).
 */
data class DiscoveredRepo(
    val owner: String,
    val repo: String,
    val description: String,
    val stars: Int,
    /** SPDX id from GitHub, or null when the repo declares no recognised licence. */
    val license: String?
) {
    fun toLibrary(): OnlineIconLibrary = OnlineIconLibrary(
        id = "gh-$owner-$repo",
        label = "$owner/$repo",
        license = license ?: "",
        owner = owner,
        repo = repo,
        pathPrefix = "",
        projectUrl = "https://github.com/$owner/$repo"
    )
}

/** One icon in a library's index; the SVG itself is fetched only when needed. */
data class OnlineIcon(
    val library: OnlineIconLibrary,
    val slug: String,
    /** The resolved repo version the index was read from — pins the CDN URL. */
    val version: String
) {
    // Community repos index nested paths — label by the file name, not the folders.
    val label: String get() = slug.substringAfterLast('/').replace('-', ' ')

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
     * One page of community repositories from GitHub's `icon-pack` topic, most-starred first
     * (30 per page). Cached per page for a day — the unauthenticated search API allows only
     * 10 requests/min, which manual browsing plus this cache stays well under. Null on
     * failure ONLY when no cache exists; a stale cache is better than an error.
     */
    suspend fun discoverRepos(page: Int): List<DiscoveredRepo>? = withContext(Dispatchers.IO) {
        val cache = File(cacheRoot, "gh-topic-page-$page.json")
        val cached = cache.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        val fresh = if (cached == null || cache.ageMs() > SEARCH_TTL_MS) {
            httpGetText(
                "https://api.github.com/search/repositories" +
                    "?q=topic:icon-pack&sort=stars&order=desc&per_page=30&page=$page",
                MAX_INDEX_BYTES
            )
        } else null
        val parsedFresh = fresh?.let { parseSearch(it) }
        if (parsedFresh != null) {
            runCatching { cache.writeText(fresh) }
            return@withContext parsedFresh
        }
        cached?.let { parseSearch(it) }
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
        private const val SEARCH_TTL_MS = 24L * 60 * 60 * 1000
        private const val MAX_INDEX_BYTES = 8 * 1024 * 1024
        private const val MAX_SVG_BYTES = 512 * 1024

        /**
         * Parses a cached index ({"version", "files":[{"name":"/icons/x.svg"}…]}) into icons:
         * .svg files under the library's path prefix, sorted by slug. Curated libraries pin a
         * folder and take only its direct children; community repos (empty prefix) take every
         * SVG wherever it sits, skipping dot-directories. Null when the JSON is unreadable.
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
                if (slug.isEmpty()) continue
                if (library.pathPrefix.isNotEmpty() && '/' in slug) continue
                if (slug.split('/').any { it.startsWith('.') || it.isEmpty() }) continue
                icons.add(OnlineIcon(library, slug, version))
            }
            icons.sortBy { it.slug }
            icons.toList()
        }.getOrNull()

        /**
         * Parses a GitHub repository-search response into browsable repos. Kotlin/Java repos
         * are dropped — those are Android icon-pack APPS, which belong in the installed-pack
         * flow, not here. "NOASSERTION" counts as no licence.
         */
        fun parseSearch(json: String): List<DiscoveredRepo>? = runCatching {
            val items = JSONObject(json).getJSONArray("items")
            val repos = mutableListOf<DiscoveredRepo>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val fullName = item.optString("full_name")
                val parts = fullName.split('/')
                if (parts.size != 2 || parts.any { it.isEmpty() }) continue
                val language = item.optString("language")
                if (language.equals("kotlin", true) || language.equals("java", true)) continue
                val spdx = item.optJSONObject("license")?.optString("spdx_id")
                    ?.takeIf { it.isNotEmpty() && it != "NOASSERTION" }
                repos.add(
                    DiscoveredRepo(
                        owner = parts[0],
                        repo = parts[1],
                        description = item.optString("description").take(120),
                        stars = item.optInt("stargazers_count"),
                        license = spdx
                    )
                )
            }
            repos.toList()
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
