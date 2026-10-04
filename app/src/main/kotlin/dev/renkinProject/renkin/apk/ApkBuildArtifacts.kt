package dev.renkinProject.renkin.apk

import android.net.Uri
import dev.renkinProject.renkin.util.Log
import java.io.File
import java.util.UUID

/** Pending installers keep their APK; completed artifacts are pruned by the next build. */
internal class ApkBuildArtifacts(cacheDir: File) {
    private val root = cacheDir.resolve("apk").canonicalFile
    private val active = mutableSetOf<File>()

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
            if (!directory.isDirectory || directory.canonicalFile.parentFile != root || directory in active) {
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
