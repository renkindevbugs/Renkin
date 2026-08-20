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
            ApkInstallResult.STORAGE,
            installFailureResult(InstallFailure.Storage("full"))
        )
        assertEquals(
            ApkInstallResult.BLOCKED,
            installFailureResult(InstallFailure.Blocked("blocked by policy"))
        )
    }

    @Test
    fun knownNonUpdatablePack_reportsConflictWithoutStartingInstall() = runBlocking {
        var installerCalled = false

        val outcome = installOrReportConflict(canUpdateInPlace = false) {
            installerCalled = true
            ApkInstallOutcome(ApkInstallResult.SUCCESS)
        }

        assertEquals(ApkInstallResult.CONFLICT, outcome.result)
        assertFalse(installerCalled)
    }

    @Test
    fun userAbortedInstall_doesNotBecomeReplacementConflict() = runBlocking {
        val outcome = installOrReportConflict(canUpdateInPlace = true) {
            ApkInstallOutcome(ApkInstallResult.ABORTED)
        }

        assertEquals(ApkInstallResult.ABORTED, outcome.result)
    }

    @Test
    fun ordinaryInstallFailure_doesNotBecomeReplacementConflict() = runBlocking {
        val outcome = installOrReportConflict(canUpdateInPlace = true) {
            ApkInstallOutcome(ApkInstallResult.FAILED, "installer error")
        }

        assertEquals(ApkInstallResult.FAILED, outcome.result)
        assertEquals("installer error", outcome.detail)
    }

    @Test
    fun cancelledUninstall_keepsOldPackAndDoesNotStartInstall() = runBlocking {
        var installerCalled = false

        val outcome = replaceAfterConflict(
            uninstall = { false },
            install = {
                installerCalled = true
                ApkInstallOutcome(ApkInstallResult.SUCCESS)
            }
        )

        assertEquals(ApkInstallResult.ABORTED, outcome.result)
        assertFalse(installerCalled)
    }

    @Test
    fun approvedSuccessfulUninstall_startsNewInstall() = runBlocking {
        var installerCalled = false

        val outcome = replaceAfterConflict(
            uninstall = { true },
            install = {
                installerCalled = true
                ApkInstallOutcome(ApkInstallResult.SUCCESS)
            }
        )

        assertEquals(ApkInstallResult.SUCCESS, outcome.result)
        assertTrue(installerCalled)
    }
}
