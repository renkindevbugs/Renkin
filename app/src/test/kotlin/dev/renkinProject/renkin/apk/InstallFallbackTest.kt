package dev.renkinProject.renkin.apk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.solrudev.ackpine.installer.InstallFailure

class InstallFallbackTest {

    @Test
    fun conflictAndAbortRemainDistinctFromOtherFailures() {
        assertEquals(
            ApkInstallResult.CONFLICT,
            installFailureResult(InstallFailure.Conflict("signature mismatch"))
        )
        assertEquals(
            ApkInstallResult.ABORTED,
            installFailureResult(InstallFailure.Aborted("cancelled"))
        )
        assertEquals(
            ApkInstallResult.FAILED,
            installFailureResult(InstallFailure.Storage("full"))
        )
    }

    @Test
    fun knownNonUpdatablePack_reportsConflictWithoutStartingInstall() = runBlocking {
        var installerCalled = false

        val result = installOrReportConflict(canUpdateInPlace = false) {
            installerCalled = true
            ApkInstallResult.SUCCESS
        }

        assertEquals(ApkInstallResult.CONFLICT, result)
        assertFalse(installerCalled)
    }

    @Test
    fun userAbortedInstall_doesNotBecomeReplacementConflict() = runBlocking {
        val result = installOrReportConflict(canUpdateInPlace = true) {
            ApkInstallResult.ABORTED
        }

        assertEquals(ApkInstallResult.ABORTED, result)
    }

    @Test
    fun ordinaryInstallFailure_doesNotBecomeReplacementConflict() = runBlocking {
        val result = installOrReportConflict(canUpdateInPlace = true) {
            ApkInstallResult.FAILED
        }

        assertEquals(ApkInstallResult.FAILED, result)
    }

    @Test
    fun cancelledUninstall_keepsOldPackAndDoesNotStartInstall() = runBlocking {
        var installerCalled = false

        val result = replaceAfterConflict(
            uninstall = { false },
            install = {
                installerCalled = true
                ApkInstallResult.SUCCESS
            }
        )

        assertEquals(ApkInstallResult.ABORTED, result)
        assertFalse(installerCalled)
    }

    @Test
    fun approvedSuccessfulUninstall_startsNewInstall() = runBlocking {
        var installerCalled = false

        val result = replaceAfterConflict(
            uninstall = { true },
            install = {
                installerCalled = true
                ApkInstallResult.SUCCESS
            }
        )

        assertEquals(ApkInstallResult.SUCCESS, result)
        assertTrue(installerCalled)
    }
}
