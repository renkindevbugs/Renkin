package dev.renkinProject.renkin.apk

import android.app.Application
import android.system.ErrnoException
import android.system.OsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BuildFailureTest {
    @Test fun fullStorageWinsEvenWhenItSurfacesThroughSigningOrEncoding() {
        val noSpace = IOException("write failed", ErrnoException("write", OsConstants.ENOSPC))

        assertEquals(BuildFailureCause.STORAGE_FULL, noSpace.buildFailureCause())
        assertEquals(BuildFailureCause.STORAGE_FULL, PackSigningException("sign", noSpace).buildFailureCause())
        assertEquals(BuildFailureCause.STORAGE_FULL, IOException("No space left on device").buildFailureCause())
    }

    @Test fun typedBuildFailuresKeepTheirOwnAdvice() {
        assertEquals(BuildFailureCause.OUT_OF_MEMORY, OutOfMemoryError().buildFailureCause())
        assertEquals(BuildFailureCause.ICON_ENCODING, IconEncodingException("res/drawable/a.webp").buildFailureCause())
        assertEquals(BuildFailureCause.SIGNING, PackSigningException("Built APK signing certificate is unavailable").buildFailureCause())
        assertEquals(BuildFailureCause.UNKNOWN, IllegalStateException("boom").buildFailureCause())
    }

    @Test fun reportNamesCausePhaseDeviceStateAndTheStackAfterABlankLine() {
        val error = PackSigningException("Built APK signing certificate is unavailable")
        val report = buildFailureReport(RuntimeEnvironment.getApplication(), error, BuildFailureCause.SIGNING, "Signing APK")

        assertTrue(report.startsWith("Renkin icon pack build report\n"))
        listOf("Android: ", "Cause: SIGNING", "Phase: Signing APK", "Memory: ", "Heap: ", "Free storage: ").forEach {
            assertTrue("missing $it", report.contains(it))
        }
        assertTrue(report.substringAfter("\n\n").startsWith(PackSigningException::class.java.name))
    }
}
