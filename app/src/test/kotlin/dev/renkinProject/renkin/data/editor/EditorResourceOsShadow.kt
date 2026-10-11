package dev.renkinProject.renkin.data.editor

import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import java.util.IdentityHashMap
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter

/** Only these resource tests simulate Android descriptors; payload reads use real JVM files. */
@Implements(Os::class)
class EditorResourceOsShadow {
    companion object {
        private data class Opened(val path: String, val file: RandomAccessFile)
        private val opened = IdentityHashMap<FileDescriptor, Opened>()
        var beforeRead: (() -> Unit)? = null
        var changedPathIdentity = false
        var readLimit = Int.MAX_VALUE
        val openCount: Int get() = opened.size

        @JvmStatic @Resetter fun reset() {
            opened.values.forEach { it.file.close() }; opened.clear()
            beforeRead = null; changedPathIdentity = false; readLimit = Int.MAX_VALUE
        }

        @JvmStatic @Implementation fun open(path: String, flags: Int, mode: Int): FileDescriptor {
            check(flags and OsConstants.O_NOFOLLOW != 0)
            // Assert the Linux flag independently of the SDK field, which is unavailable before API 27.
            check(flags and 0x80000 != 0)
            return FileDescriptor().also { opened[it] = Opened(path, RandomAccessFile(path, "r")) }
        }

        private fun info(path: String, size: Long = File(path).length(), changed: Boolean = false): StructStat = StructStat(
            1, path.hashCode().toLong() + if (changed) 1 else 0, OsConstants.S_IFREG,
            1, 0, 0, 0, size, 0, File(path).lastModified() / 1000, 0, 8192, 1
        )

        @JvmStatic @Implementation fun fstat(descriptor: FileDescriptor): StructStat {
            val input = checkNotNull(opened[descriptor]); return info(input.path, input.file.length())
        }
        @JvmStatic @Implementation fun stat(path: String): StructStat = info(path, changed = changedPathIdentity)
        @JvmStatic @Implementation fun read(descriptor: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int {
            beforeRead?.also { beforeRead = null; it() }
            return checkNotNull(opened[descriptor]).file.read(bytes, offset, minOf(count, readLimit)).coerceAtLeast(0)
        }
        @JvmStatic @Implementation fun close(descriptor: FileDescriptor) { checkNotNull(opened.remove(descriptor)).file.close() }
    }
}
