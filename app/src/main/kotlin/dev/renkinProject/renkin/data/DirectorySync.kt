package dev.renkinProject.renkin.data

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/** A create or rename inside [directory] is only durable after the directory itself is synced. */
internal fun syncDirectory(directory: File) {
    if (!directory.isDirectory) throw IOException("Cannot sync missing directory ${directory.name}")
    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
}
