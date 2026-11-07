package dev.renkinProject.renkin.data.online

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.renkinProject.renkin.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class DownloadedIconSet(val prefix: String, val version: String, val bytes: Long)

/** One pinned `@iconify-json/<prefix>` release with the hashes and sizes jsDelivr reports for it. */
data class IconSetRelease(
    val prefix: String,
    val version: String,
    val iconsSha256: String,
    val iconsBytes: Long,
    val infoSha256: String?
)

sealed interface IconSetReleaseLookup {
    data class Found(val release: IconSetRelease) : IconSetReleaseLookup
    /** No npm package for the set, or it is larger than [DownloadedIconSets.MAX_SET_BYTES]. */
    data object NotAvailable : IconSetReleaseLookup
    data object Failed : IconSetReleaseLookup
}

sealed interface IconSetDownloadResult {
    data object Done : IconSetDownloadResult
    data object NotAvailable : IconSetDownloadResult
    /** Size, hash or content did not match the release; nothing was kept. */
    data object IntegrityFailed : IconSetDownloadResult
    data object Failed : IconSetDownloadResult
}

/**
 * Whole icon sets the user downloaded for offline use, as the unmodified `icons.json` of a
 * version-pinned `@iconify-json/<prefix>` npm package from jsDelivr, checked against the SHA-256
 * that data.jsdelivr.com lists (this guards against damaged or truncated transfers).
 *
 * Layout: `filesDir/online-icon-sets/<prefix>/icons-<version>.json` (+ `info-<version>.json`) and
 * `manifest.json`, which is written last and names the current files. A folder without a valid
 * manifest, and every file it does not name, is leftover from an interrupted download and is
 * deleted on the next scan. Excluded from Auto Backup: sets can be downloaded again.
 * Downloads run only when the user asks, never in the background.
 */
@Singleton
class DownloadedIconSets internal constructor(
    private val rootDir: () -> File,
    private val transport: suspend (url: String, maxBytes: Long, sink: OutputStream, onBytes: (Long) -> Unit) -> Int?,
    private val ioDispatcher: CoroutineDispatcher
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        { File(context.filesDir, "online-icon-sets") }, ::httpDownload, Dispatchers.IO
    )

    private val mutableSets = MutableStateFlow<Map<String, DownloadedIconSet>>(emptyMap())
    val sets: StateFlow<Map<String, DownloadedIconSet>> = mutableSets.asStateFlow()

    private val stateLock = Mutex()
    private val downloadLock = Mutex()
    @Volatile private var scanned = false
    // Only one downloaded set stays parsed: a large set holds several megabytes of icon bodies.
    @Volatile private var opened: IconifySet? = null

    suspend fun ensureScanned() {
        if (scanned) return
        withContext(ioDispatcher) {
            stateLock.withLock {
                if (scanned) return@withLock
                mutableSets.value = rootDir().listFiles().orEmpty()
                    .filter { it.isDirectory }
                    .mapNotNull(::readValidManifest)
                    .associate { it.set.prefix to it.set }
                scanned = true
            }
        }
    }

    internal suspend fun set(prefix: String): IconifySet? {
        ensureScanned()
        if (prefix !in mutableSets.value) return null
        opened?.takeIf { it.prefix == prefix }?.let { return it }
        return withContext(ioDispatcher) {
            stateLock.withLock {
                opened?.takeIf { it.prefix == prefix } ?: run {
                    val manifest = readValidManifest(File(rootDir(), prefix)) ?: return@withLock null
                    runCatching {
                        manifest.icons.reader(Charsets.UTF_8).buffered().use(IconifyJson::parse)
                    }.getOrNull()?.takeIf { it.prefix == prefix }?.also { opened = it }
                }
            }
        }
    }

    /** Set-list entries from each downloaded set's `info.json`. */
    suspend fun collections(): List<IconifyCollection> {
        ensureScanned()
        return withContext(ioDispatcher) {
            mutableSets.value.keys.mapNotNull { prefix ->
                val info = readValidManifest(File(rootDir(), prefix))?.info ?: return@mapNotNull null
                runCatching { OnlineIconRepository.parseCollections("{\"$prefix\":${info.readText()}}") }
                    .getOrNull()?.singleOrNull()
            }
        }
    }

    suspend fun latestRelease(prefix: String): IconSetReleaseLookup = withContext(ioDispatcher) {
        if (!ICONIFY_PREFIX.matches(prefix)) return@withContext IconSetReleaseLookup.NotAvailable
        val resolved = fetchText("$JSDELIVR_DATA$prefix/resolved")
        if (resolved.status == HttpURLConnection.HTTP_NOT_FOUND) return@withContext IconSetReleaseLookup.NotAvailable
        val version = resolved.body?.let { runCatching { JSONObject(it).getString("version") }.getOrNull() }
            ?: return@withContext IconSetReleaseLookup.Failed
        val listing = fetchText("$JSDELIVR_DATA$prefix@$version?structure=flat").body
            ?: return@withContext IconSetReleaseLookup.Failed
        val files = runCatching {
            val array = JSONObject(listing).getJSONArray("files")
            (0 until array.length()).associate { i ->
                val file = array.getJSONObject(i)
                file.getString("name") to (file.getString("hash") to file.getLong("size"))
            }
        }.getOrNull() ?: return@withContext IconSetReleaseLookup.Failed
        val (iconsHash, iconsBytes) = files["/icons.json"] ?: return@withContext IconSetReleaseLookup.NotAvailable
        if (iconsBytes > MAX_SET_BYTES) return@withContext IconSetReleaseLookup.NotAvailable
        IconSetReleaseLookup.Found(IconSetRelease(prefix, version, iconsHash, iconsBytes, files["/info.json"]?.first))
    }

    suspend fun download(release: IconSetRelease, onProgress: (Float) -> Unit): IconSetDownloadResult =
        withContext(ioDispatcher) {
            downloadLock.withLock { downloadLocked(release, onProgress) }
        }

    private suspend fun downloadLocked(release: IconSetRelease, onProgress: (Float) -> Unit): IconSetDownloadResult {
        val prefix = release.prefix
        if (!ICONIFY_PREFIX.matches(prefix) || !ICONIFY_VERSION.matches(release.version)) {
            return IconSetDownloadResult.NotAvailable
        }
        val dir = File(rootDir(), prefix).apply { mkdirs() }
        val icons = File(dir, "icons-${release.version}.json")
        val part = File(dir, "${icons.name}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val status = try {
                DigestOutputStream(part.outputStream().buffered(), digest).use { sink ->
                    transport("$JSDELIVR_CDN$prefix@${release.version}/icons.json", MAX_SET_BYTES, sink) { bytes ->
                        onProgress((bytes.toFloat() / release.iconsBytes).coerceIn(0f, 1f))
                    }
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                Log.error("OnlineIcons", "Icon set download failed: $prefix", e)
                null
            }
            when {
                status == HttpURLConnection.HTTP_NOT_FOUND -> return IconSetDownloadResult.NotAvailable
                status == TOO_LARGE -> return IconSetDownloadResult.IntegrityFailed
                status != HttpURLConnection.HTTP_OK -> return IconSetDownloadResult.Failed
            }
            if (part.length() != release.iconsBytes || base64(digest.digest()) != release.iconsSha256) {
                Log.error("OnlineIcons", "Icon set did not match its release: $prefix@${release.version}")
                return IconSetDownloadResult.IntegrityFailed
            }
            val parsed = runCatching { part.reader(Charsets.UTF_8).buffered().use(IconifyJson::parse) }.getOrNull()
                ?.takeIf { it.prefix == prefix && it.visibleNames.isNotEmpty() }
                ?: return IconSetDownloadResult.IntegrityFailed
            val info = release.infoSha256?.let { downloadInfo(release, it, dir) }
            if (!part.renameTo(icons)) return IconSetDownloadResult.Failed
            val manifest = Manifest(DownloadedIconSet(prefix, release.version, release.iconsBytes), icons, info)
            if (!writeManifest(dir, manifest)) return IconSetDownloadResult.Failed
            stateLock.withLock {
                dir.listFiles().orEmpty()
                    .filter { it.name != MANIFEST && it != icons && it != info }
                    .forEach { it.delete() }
                opened = parsed
                mutableSets.value = mutableSets.value + (prefix to manifest.set)
            }
            return IconSetDownloadResult.Done
        } finally {
            part.delete()
        }
    }

    /** `info.json` only names the set offline; a failed copy never fails the download. */
    private suspend fun downloadInfo(release: IconSetRelease, sha256: String, dir: File): File? {
        val buffer = ByteArrayOutputStream()
        val status = runCatching {
            transport("$JSDELIVR_CDN${release.prefix}@${release.version}/info.json", MAX_INFO_BYTES, buffer) {}
        }.getOrElse { if (it is CancellationException) throw it else null }
        val bytes = buffer.toByteArray()
        if (status != HttpURLConnection.HTTP_OK || base64(sha256(bytes)) != sha256) return null
        return File(dir, "info-${release.version}.json").takeIf { file ->
            runCatching { file.writeBytes(bytes) }.isSuccess
        }
    }

    suspend fun delete(prefix: String) = withContext(ioDispatcher) {
        downloadLock.withLock {
            stateLock.withLock {
                if (ICONIFY_PREFIX.matches(prefix)) File(rootDir(), prefix).deleteRecursively()
                if (opened?.prefix == prefix) opened = null
                mutableSets.value = mutableSets.value - prefix
            }
        }
    }

    private class Manifest(val set: DownloadedIconSet, val icons: File, val info: File?)

    private fun readValidManifest(dir: File): Manifest? {
        val manifest = runCatching { JSONObject(AtomicFile(File(dir, MANIFEST)).readFully().decodeToString()) }
            .getOrNull()
        val parsed = manifest?.let { json ->
            runCatching {
                val icons = File(dir, json.getString("icons"))
                val bytes = json.getLong("bytes")
                val info = json.optString("info").takeIf(String::isNotEmpty)?.let { File(dir, it) }
                Manifest(DownloadedIconSet(dir.name, json.getString("version"), bytes), icons, info?.takeIf(File::isFile))
                    .takeIf { icons.isFile && icons.length() == bytes && json.getString("prefix") == dir.name }
            }.getOrNull()
        }
        if (parsed == null) {
            dir.deleteRecursively()
            return null
        }
        dir.listFiles().orEmpty()
            .filter { it.name != MANIFEST && it != parsed.icons && it != parsed.info }
            .forEach { it.delete() }
        return parsed
    }

    private fun writeManifest(dir: File, manifest: Manifest): Boolean {
        val json = JSONObject()
            .put("prefix", manifest.set.prefix)
            .put("version", manifest.set.version)
            .put("bytes", manifest.set.bytes)
            .put("icons", manifest.icons.name)
            .put("info", manifest.info?.name.orEmpty())
        val atomic = AtomicFile(File(dir, MANIFEST))
        var output: java.io.FileOutputStream? = null
        return try {
            output = atomic.startWrite()
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            true
        } catch (e: IOException) {
            output?.let(atomic::failWrite)
            Log.error("OnlineIcons", "Could not write icon set manifest: ${manifest.set.prefix}", e)
            false
        }
    }

    private suspend fun fetchText(url: String): TextResult {
        val buffer = ByteArrayOutputStream()
        val status = try {
            transport(url, MAX_LISTING_BYTES, buffer) {}
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            Log.error("OnlineIcons", "GET failed: $url", e)
            null
        }
        return TextResult(status, buffer.toString(Charsets.UTF_8.name()).takeIf { status == HttpURLConnection.HTTP_OK })
    }

    private class TextResult(val status: Int?, val body: String?)

    companion object {
        /** 16 MiB raw: the largest sets (Fluent with every size, about 12.7 MB) still fit. */
        const val MAX_SET_BYTES = 16L * 1024 * 1024
        private const val MAX_INFO_BYTES = 64L * 1024
        private const val MAX_LISTING_BYTES = 256L * 1024
        private const val MANIFEST = "manifest.json"
        private const val TOO_LARGE = -1
        private const val JSDELIVR_CDN = "https://cdn.jsdelivr.net/npm/@iconify-json/"
        private const val JSDELIVR_DATA = "https://data.jsdelivr.com/v1/packages/npm/@iconify-json/"
        private val ICONIFY_PREFIX = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
        private val ICONIFY_VERSION = Regex("^[0-9A-Za-z.+-]+$")

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        /** Streams a GET into [sink]; returns the HTTP status, or [TOO_LARGE] past [maxBytes]. */
        private suspend fun httpDownload(
            url: String,
            maxBytes: Long,
            sink: OutputStream,
            onBytes: (Long) -> Unit
        ): Int? = withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                // No Accept-Encoding: HttpURLConnection then requests gzip and inflates it itself.
                setRequestProperty("User-Agent", "Renkin (Android)")
            }
            // Disconnect while the blocking copy is still running when its caller is cancelled.
            coroutineScope {
                val copy = async {
                    runInterruptible {
                        val status = connection.responseCode
                        if (status != HttpURLConnection.HTTP_OK) return@runInterruptible status
                        connection.inputStream.use { input ->
                            val chunk = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("GET cancelled")
                                val read = input.read(chunk)
                                if (read < 0) break
                                total += read
                                if (total > maxBytes) return@runInterruptible TOO_LARGE
                                sink.write(chunk, 0, read)
                                onBytes(total)
                            }
                        }
                        status
                    }
                }
                try {
                    copy.await()
                } finally {
                    connection.disconnect()
                }
            }
        }
    }
}
