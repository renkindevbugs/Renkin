package dev.renkinProject.renkin.data.transfer

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.IOException
import java.io.File
import java.io.FileDescriptor
import java.util.IdentityHashMap
import org.robolectric.annotation.Resetter
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

// Robolectric's Linux shadow does not implement rename. Shadow the static facade to
// avoid reusing Libcore's boot-time Linux instance with a different shadow in a full suite.
@Implements(Os::class)
class BackupOsShadow {
    companion object {
        private val directories = IdentityHashMap<FileDescriptor, String>()
        var rejectRename: ((String, String) -> Boolean)? = null
        var beforeDirectorySync: ((String) -> Unit)? = null

        @JvmStatic @Resetter
        fun reset() { directories.clear(); rejectRename = null; beforeDirectorySync = null }

        // Windows cannot open directories via FileInputStream. Keep the Android directory
        // descriptor lifecycle testable; payload FileDescriptor.sync still runs normally.
        @JvmStatic @Implementation
        fun open(path: String, flags: Int, mode: Int): FileDescriptor {
            if (!File(path).isDirectory) throw ErrnoException("open", OsConstants.ENOTDIR)
            return FileDescriptor().also { directories[it] = path }
        }

        @JvmStatic @Implementation
        fun fsync(descriptor: FileDescriptor) {
            val path = checkNotNull(directories[descriptor])
            beforeDirectorySync?.invoke(path)
        }

        @JvmStatic @Implementation
        fun close(descriptor: FileDescriptor) {
            check(directories.remove(descriptor) != null)
        }

        @JvmStatic
        @Implementation
        fun rename(source: String, target: String) {
            if (rejectRename?.invoke(source, target) == true) throw ErrnoException("rename", OsConstants.EIO)
            try {
                Files.move(Paths.get(source), Paths.get(target), StandardCopyOption.REPLACE_EXISTING)
            } catch (error: IOException) {
                throw ErrnoException("rename", OsConstants.EIO, error)
            }
        }
    }
}
