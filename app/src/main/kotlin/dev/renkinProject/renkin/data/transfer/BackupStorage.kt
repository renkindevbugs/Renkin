package dev.renkinProject.renkin.data.transfer

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.IOException

class BackupSpaceException : IOException("Not enough free storage for backup staging")
class BackupSizeException : IOException("Backup exceeds supported size limits")
class BackupMemoryException(cause: OutOfMemoryError) : IOException("Not enough memory to process this backup", cause)

internal fun ensureBackupSpace(directory: File, requiredBytes: Long = 0) {
    if (StatFs(directory.absolutePath).availableBytes < requiredBytes + 32L * 1024 * 1024) {
        throw BackupSpaceException()
    }
}

internal fun ensureBackupRestoreSpace(context: Context, incomingDataBytes: Long) {
    val databaseBytes = context.databaseList().filterNot {
        it.endsWith("-wal") || it.endsWith("-shm") || it.endsWith("-journal")
    }.sumOf { name ->
        val database = context.getDatabasePath(name)
        database.length() + File(database.path + "-wal").length()
    }
    // Replace-all can retain old pages while writing new rows to the WAL. JSON payload
    // bytes estimate the incoming rows; this is a reserve, not a SQLite size guarantee.
    ensureBackupSpace(context.filesDir, databaseBytes + incomingDataBytes)
}
