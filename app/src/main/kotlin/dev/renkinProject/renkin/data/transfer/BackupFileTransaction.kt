package dev.renkinProject.renkin.data.transfer

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal class BackupFileTransaction private constructor(
    private val directory: File,
    private val entries: List<Entry>
) : Closeable {
    val batchName: String get() = directory.name
    private var canClean = true

    private class Entry(val target: File, val replacement: File?, val previous: File) {
        var movedPrevious = false
        var installed = false
    }

    fun apply() {
        canClean = false
        for (entry in entries) {
            if (entry.target.exists()) {
                move(entry.target, entry.previous)
                entry.movedPrevious = true
            }
            entry.replacement?.let {
                move(it, entry.target)
                entry.installed = true
            }
        }
    }

    fun rollback() {
        var failure: IOException? = null
        for (entry in entries.asReversed()) {
            try {
                if (entry.movedPrevious) {
                    move(entry.previous, entry.target)
                    entry.movedPrevious = false
                    entry.installed = false
                } else if (entry.installed) {
                    if (entry.target.exists() && !entry.target.delete()) throw IOException("Cannot remove restored file")
                    entry.installed = false
                }
            } catch (error: IOException) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let {
            Log.error("BackupFileTransaction", "File rollback failed; preserved originals in ${directory.name}", it)
            throw it
        }
        canClean = true
    }

    fun finish() {
        canClean = true
    }

    fun sync() {
        (entries.mapNotNull { it.target.parentFile } + directory).distinct().forEach { parent ->
            if (!parent.isDirectory) throw IOException("Cannot sync file transaction directory")
            val descriptor = Os.open(parent.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        }
    }

    override fun close() {
        // Recovery owns retained originals until the full storage rollback finishes.
        BackupStaging.release(directory, delete = canClean)
    }

    companion object {
        suspend fun prepare(
            filesDir: File,
            replacements: Map<File, File?>,
            onDirectoryCreated: (String) -> Unit = {}
        ): BackupFileTransaction {
            val directory = BackupStaging.create(filesDir, "backup-file-commit-")
            try {
                onDirectoryCreated(directory.name)
                ensureBackupSpace(filesDir)
                val entries = replacements.entries.mapIndexed { index, (target, source) ->
                    currentCoroutineContext().ensureActive()
                    if (!target.parentFile!!.isDirectory || target.isDirectory) throw IOException("Invalid restore target")
                    val replacement = source?.let { original ->
                        val staged = File(directory, "new-$index")
                        transferStagedFile(original, staged)
                        staged
                    }
                    Entry(target, replacement, File(directory, "old-$index"))
                }
                return BackupFileTransaction(directory, entries)
            } catch (error: Throwable) {
                BackupStaging.release(directory)
                throw error
            }
        }

        // Preparation consumes the staged files; the import handle is released after this
        // attempt even when preparation is cancelled. No live storage has changed yet.
        private suspend fun transferStagedFile(source: File, target: File) {
            if (!source.isFile) throw IOException("Missing staged restore file")
            FileOutputStream(source, true).use { it.fd.sync() }
            currentCoroutineContext().ensureActive()
            try {
                Os.rename(source.absolutePath, target.absolutePath)
                return
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EXDEV) throw IOException("Cannot move staged restore file", error)
            }

            ensureBackupSpace(target.parentFile!!, source.length())
            source.inputStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytes = 0L
                    var nextSpaceCheck = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes >= nextSpaceCheck) {
                            ensureBackupSpace(target.parentFile!!, 1024 * 1024L)
                            nextSpaceCheck = bytes + 1024 * 1024
                        }
                        output.write(buffer, 0, count)
                        bytes += count
                    }
                    output.fd.sync()
                }
            }
        }

        private fun move(source: File, target: File) {
            try {
                Os.rename(source.absolutePath, target.absolutePath)
            } catch (error: ErrnoException) {
                throw IOException("Cannot replace ${target.name}", error)
            }
        }
    }
}
