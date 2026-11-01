package dev.renkinProject.renkin.apk

import android.net.Uri
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.UUID

internal data class ApkExportLease(val source: Uri, val token: String)

/** Pending installers keep their APK; completed artifacts are pruned by the next build. */
internal class ApkBuildArtifacts(cacheDir: File) {
    private val root = cacheDir.resolve("apk").canonicalFile
    private val active = mutableSetOf<File>()
    // Exports of a released APK pin its directory independently of installer ownership.
    private val exporting = mutableMapOf<File, Int>()

    // The disk marker outlives the process while Android's document picker owns the UI.
    @Synchronized
    fun reserveExport(uri: Uri): ApkExportLease? {
        val source = artifactFile(uri)?.takeIf { it.isFile } ?: return null
        val lease = ApkExportLease(uri, UUID.randomUUID().toString())
        check(requireNotNull(exportMarker(lease)).createNewFile())
        return lease
    }

    @Synchronized
    fun releaseExport(lease: ApkExportLease) {
        exportMarker(lease)?.let { marker -> check(marker.delete() || !marker.exists()) }
    }

    private fun exportMarker(lease: ApkExportLease): File? {
        if (runCatching { UUID.fromString(lease.token).toString() }.getOrNull() != lease.token) return null
        return artifactFile(lease.source)?.parentFile?.resolve(".export-${lease.token}")
    }

    /**
     * Copies a still-present artifact into [openTarget]'s stream while pinning it against pruning.
     * False when the artifact is already gone or the target cannot be opened; I/O errors propagate.
     */
    suspend fun export(lease: ApkExportLease, openTarget: () -> OutputStream?): Boolean = withContext(Dispatchers.IO) {
        val source = retainForExport(lease) ?: return@withContext false
        try {
            val output = openTarget() ?: return@withContext false
            output.use { sink ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                    }
                }
            }
            true
        } finally {
            try { releaseExport(lease) } finally { finishExport(source) }
        }
    }

    @Synchronized
    private fun retainForExport(lease: ApkExportLease): File? {
        if (exportMarker(lease)?.isFile != true) return null
        val file = artifactFile(lease.source)?.takeIf { it.isFile } ?: return null
        val directory = file.parentFile ?: return null
        exporting[directory] = (exporting[directory] ?: 0) + 1
        return file
    }

    @Synchronized
    private fun finishExport(file: File) {
        val directory = file.parentFile ?: return
        val remaining = (exporting[directory] ?: return) - 1
        if (remaining > 0) exporting[directory] = remaining else exporting -= directory
    }

    private fun artifactFile(uri: Uri): File? {
        if (uri.scheme != "file") return null
        val file = uri.path?.let { File(it).canonicalFile } ?: return null
        return file.takeIf { it.parentFile?.parentFile == root }
    }

    @Synchronized
    fun create(): File {
        prune()
        return root.resolve(UUID.randomUUID().toString()).also { directory ->
            check(directory.mkdirs() || directory.isDirectory)
            active += directory
        }
    }

    @Synchronized
    fun abandon(directory: File) {
        active -= directory
        delete(directory)
    }

    @Synchronized
    fun release(uri: Uri, completed: Boolean) {
        if (uri.scheme != "file") return
        val directory = uri.path?.let { File(it).canonicalFile.parentFile } ?: return
        if (directory.parentFile != root || directory !in active) return
        active -= directory
        if (completed) runCatching { directory.resolve(".finished").createNewFile() }
            .onFailure { Log.error("ApkBuildArtifacts", "Could not mark completed APK", it) }
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        root.listFiles()?.forEach { directory ->
            if (directory.isFile && directory.name in setOf("app-release.apk", "app-release-unsigned.apk") &&
                directory.lastModified() < cutoff) {
                delete(directory)
                return@forEach
            }
            if (!directory.isDirectory || directory.canonicalFile.parentFile != root || directory in active ||
                directory in exporting) {
                return@forEach
            }
            // Abandoned picker leases expire with the existing orphan grace period.
            if (directory.listFiles()?.any { it.name.startsWith(".export-") && it.lastModified() >= cutoff } == true) {
                return@forEach
            }
            // Unmarked files may belong to an installer surviving a process restart or timeout.
            if (directory.resolve(".finished").exists() || directory.lastModified() < cutoff) delete(directory)
        }
    }

    private fun delete(directory: File) {
        runCatching { check(directory.deleteRecursively() || !directory.exists()) }
            .onFailure { Log.error("ApkBuildArtifacts", "Could not remove APK artifacts", it) }
    }
}
