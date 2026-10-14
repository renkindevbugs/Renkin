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

/** Pending installers keep their APK; completed artifacts are pruned by the next build. */
internal class ApkBuildArtifacts(cacheDir: File) {
    private val root = cacheDir.resolve("apk").canonicalFile
    private val active = mutableSetOf<File>()
    // Exports of a released APK pin its directory independently of installer ownership.
    private val exporting = mutableMapOf<File, Int>()

    /**
     * Copies a still-present artifact into [openTarget]'s stream while pinning it against pruning.
     * False when the artifact is already gone or the target cannot be opened; I/O errors propagate.
     */
    suspend fun export(uri: Uri, openTarget: () -> OutputStream?): Boolean = withContext(Dispatchers.IO) {
        val source = retainForExport(uri) ?: return@withContext false
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
            finishExport(source)
        }
    }

    @Synchronized
    private fun retainForExport(uri: Uri): File? {
        val file = artifactFile(uri)?.takeIf { it.isFile } ?: return null
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
            // Unmarked files may belong to an installer surviving a process restart or timeout.
            if (directory.resolve(".finished").exists() || directory.lastModified() < cutoff) delete(directory)
        }
    }

    private fun delete(directory: File) {
        runCatching { check(directory.deleteRecursively() || !directory.exists()) }
            .onFailure { Log.error("ApkBuildArtifacts", "Could not remove APK artifacts", it) }
    }
}
