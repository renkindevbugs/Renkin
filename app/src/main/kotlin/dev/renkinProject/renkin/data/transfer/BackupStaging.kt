package dev.renkinProject.renkin.data.transfer

import dev.renkinProject.renkin.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID

internal object BackupStaging {
    private val activeDirectories = mutableSetOf<String>()

    fun create(root: File, prefix: String): File = synchronized(activeDirectories) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create backup staging directory")
        val directory = File(root, "$prefix${UUID.randomUUID()}")
        if (!directory.mkdirs()) throw IOException("Cannot stage backup files")
        activeDirectories.add(directory.absolutePath)
        directory
    }

    fun release(directory: File, delete: Boolean = true) = synchronized(activeDirectories) {
        if (delete && directory.exists() && !directory.deleteRecursively()) {
            Log.error("BackupStaging", "Could not remove backup staging directory ${directory.name}")
        }
        activeDirectories.remove(directory.absolutePath)
        Unit
    }

    fun cleanAfterProcessStart(filesDir: File) = synchronized(activeDirectories) {
        // Creation and cleanup share a lock: a delayed startup job must not delete a live
        // preview, worker export or file transaction started in this process.
        val abandoned = File(filesDir, "backup-imports").listFiles { file ->
            file.isDirectory && (file.name.startsWith("backup-import-") || file.name.startsWith("backup-export-")) &&
                file.absolutePath !in activeDirectories
        }.orEmpty()
        var removed = 0
        for (directory in abandoned) {
            if (directory.deleteRecursively()) removed++
            else Log.error("BackupStaging", "Could not remove abandoned staging directory ${directory.name}")
        }
        if (removed > 0) Log.info("BackupStaging", "Removed abandoned backup staging directories: $removed")

        val pendingRecovery = filesDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("backup-file-commit-") && file.absolutePath !in activeDirectories
        }.orEmpty()
        if (pendingRecovery.isNotEmpty()) {
            // These may contain the only remaining signing credentials or gallery originals.
            // Phase 3B must recover them using a durable transaction marker.
            Log.error("BackupStaging", "Preserved backup file transactions requiring recovery: ${pendingRecovery.size}")
        }
        Unit
    }
}
