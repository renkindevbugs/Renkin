package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PackageInstallSignalTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val packageName = "example.generated.pack"

    private fun broadcast(action: String, target: String = packageName) {
        application.sendBroadcast(Intent(action, Uri.parse("package:$target")))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun firstInstallAndReplacementWakeTheirOwnAttempt() = runBlocking {
        PackageInstallSignal(application, packageName).use { signal ->
            broadcast(Intent.ACTION_PACKAGE_ADDED)
            assertTrue(signal.awaitChange(1_000))
            broadcast(Intent.ACTION_PACKAGE_REPLACED)
            assertTrue(signal.awaitChange(1_000))
        }
    }

    @Test
    fun wrongPackageAndRemovalDoNotWakeTheVerifier() = runBlocking {
        PackageInstallSignal(application, packageName).use { signal ->
            broadcast(Intent.ACTION_PACKAGE_ADDED, "example.other.pack")
            broadcast(Intent.ACTION_PACKAGE_REMOVED)
            assertFalse(signal.awaitChange(50))
        }
    }

    @Test
    fun duplicateBroadcastsAreConflatedAndMissingBroadcastUsesTimeout() = runBlocking {
        PackageInstallSignal(application, packageName).use { signal ->
            broadcast(Intent.ACTION_PACKAGE_ADDED)
            broadcast(Intent.ACTION_PACKAGE_REPLACED)
            assertTrue(signal.awaitChange(1_000))
            assertFalse(signal.awaitChange(50))
        }
    }

    @Test
    fun closingAnAttemptUnregistersItAndCannotWakeTheNextAttempt() = runBlocking {
        val before = shadowOf(application).registeredReceivers.size
        val previous = PackageInstallSignal(application, packageName)
        assertEquals(before + 1, shadowOf(application).registeredReceivers.size)
        broadcast(Intent.ACTION_PACKAGE_ADDED)
        previous.close()
        assertEquals(before, shadowOf(application).registeredReceivers.size)
        PackageInstallSignal(application, packageName).use { next ->
            assertFalse(next.awaitChange(50))
        }
        assertEquals(before, shadowOf(application).registeredReceivers.size)
    }
}
