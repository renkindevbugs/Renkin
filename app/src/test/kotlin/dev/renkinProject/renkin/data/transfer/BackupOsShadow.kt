package dev.renkinProject.renkin.data.transfer

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

// Robolectric's Linux shadow does not implement rename. Shadow the static facade to
// avoid reusing Libcore's boot-time Linux instance with a different shadow in a full suite.
@Implements(Os::class)
class BackupOsShadow {
    companion object {
        @JvmStatic
        @Implementation
        fun rename(source: String, target: String) {
            try {
                Files.move(Paths.get(source), Paths.get(target), StandardCopyOption.REPLACE_EXISTING)
            } catch (error: IOException) {
                throw ErrnoException("rename", OsConstants.EIO, error)
            }
        }
    }
}
