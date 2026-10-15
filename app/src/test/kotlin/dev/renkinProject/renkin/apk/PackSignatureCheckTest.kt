package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.pm.PackageInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PackSignatureCheckTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val packageName = "example.generated.pack"

    @Test
    fun aPackThatIsNotInstalledCanAlwaysBeInstalled() {
        assertEquals(true, canUpdateWithSameSigner(application.packageManager, packageName, "/missing.apk"))
    }

    @Test
    fun unreadableBuiltApkLeavesTheDecisionToAndroid() {
        shadowOf(application.packageManager).installPackage(PackageInfo().apply { packageName = this@PackSignatureCheckTest.packageName })
        val missing = File(application.cacheDir, "missing.apk").absolutePath
        assertNull(canUpdateWithSameSigner(application.packageManager, packageName, missing))
    }

    @Test
    fun definiteSignerMismatchReportsAConflictWithoutInstalling() = runBlocking {
        val outcome = installOrReportConflict(canUpdateInPlace = true, sameSigner = false) {
            error("A mismatching signer must not start an installer")
        }
        assertEquals(ApkInstallResult.CONFLICT, outcome.result)
        assertEquals(SIGNATURE_MISMATCH_DETAIL, outcome.detail)
    }

    @Test
    fun unknownOrMatchingSignerKeepsTheExistingDecision() = runBlocking {
        var installs = 0
        val success = ApkInstallOutcome(ApkInstallResult.SUCCESS)
        assertEquals(ApkInstallResult.SUCCESS, installOrReportConflict(true, sameSigner = null) { installs++; success }.result)
        assertEquals(ApkInstallResult.SUCCESS, installOrReportConflict(true, sameSigner = true) { installs++; success }.result)
        val versionConflict = installOrReportConflict(false, sameSigner = true) { installs++; success }
        assertEquals(ApkInstallResult.CONFLICT, versionConflict.result)
        assertNull(versionConflict.detail)
        assertTrue(installs == 2)
    }
}
