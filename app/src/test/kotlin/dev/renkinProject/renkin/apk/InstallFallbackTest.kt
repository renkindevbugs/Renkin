package dev.renkinProject.renkin.apk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.solrudev.ackpine.installer.InstallFailure

class InstallFallbackTest {

    @Test
    fun deadInstallerSession_isRecognizedWithoutMatchingOtherFailures() {
        assertTrue(
            isDeadInstallerSession(
                ApkInstallOutcome(ApkInstallResult.FAILED, "Session 1866153790 is dead.")
            )
        )
        assertFalse(
            isDeadInstallerSession(
                ApkInstallOutcome(ApkInstallResult.FAILED, "Session timed out")
            )
        )
        assertFalse(
            isDeadInstallerSession(
                ApkInstallOutcome(ApkInstallResult.SUCCESS, "Session 1 is dead.")
            )
        )
    }

    @Test
    fun installationChanged_acceptsSameVersionReinstallWithNewTimestamp() {
        val installed = InstalledPackageState(versionCode = 4L, lastUpdateTime = 1_000L)

        assertTrue(installationChanged(previous = null, current = installed))
        assertTrue(
            installationChanged(
                previous = installed,
                current = InstalledPackageState(versionCode = 5L, lastUpdateTime = 1_100L)
            )
        )
        assertTrue(
            installationChanged(
                previous = installed,
                current = InstalledPackageState(versionCode = 4L, lastUpdateTime = 1_100L)
            )
        )
        assertFalse(installationChanged(previous = installed, current = installed))
        assertFalse(installationChanged(previous = installed, current = null))
    }

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
    fun failedUninstall_reportsTheCallersOutcomeWithoutInstalling() = runBlocking {
        var installerCalled = false

        val outcome = replaceAfterConflict(
            uninstall = { false },
            install = {
                installerCalled = true
                ApkInstallOutcome(ApkInstallResult.SUCCESS)
            },
            uninstallFailed = { ApkInstallOutcome(ApkInstallResult.FAILED, "record") }
        )

        assertEquals(ApkInstallOutcome(ApkInstallResult.FAILED, "record"), outcome)
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

    @Test
    fun onlyTechnicalFailuresMoveOnToTheNextRoute() {
        val terminal = listOf(
            ApkInstallResult.ABORTED, ApkInstallResult.CONFLICT, ApkInstallResult.INCOMPATIBLE,
            ApkInstallResult.INVALID, ApkInstallResult.STORAGE, ApkInstallResult.TIMEOUT,
            ApkInstallResult.NO_SYSTEM_INSTALLER, ApkInstallResult.SUCCESS
        )
        for (backend in ApkInstallBackend.entries) {
            for (result in terminal) assertFalse("$backend $result", shouldTryNextRoute(backend, result))
        }
        assertTrue(shouldTryNextRoute(ApkInstallBackend.SYSTEM_SESSION, ApkInstallResult.FAILED))
        assertTrue(shouldTryNextRoute(ApkInstallBackend.EXTERNAL, ApkInstallResult.FAILED))
        assertTrue(shouldTryNextRoute(ApkInstallBackend.SHIZUKU, ApkInstallResult.FAILED))
        assertFalse(shouldTryNextRoute(ApkInstallBackend.COMPATIBILITY, ApkInstallResult.FAILED))
    }

    @Test
    fun onlyShizukuTreatsAUserRestrictionAsPassableByTheSystemInstaller() {
        assertTrue(shouldTryNextRoute(ApkInstallBackend.SHIZUKU, ApkInstallResult.BLOCKED))
        assertFalse(shouldTryNextRoute(ApkInstallBackend.SYSTEM_SESSION, ApkInstallResult.BLOCKED))
        assertFalse(shouldTryNextRoute(ApkInstallBackend.EXTERNAL, ApkInstallResult.BLOCKED))
        assertFalse(shouldTryNextRoute(ApkInstallBackend.COMPATIBILITY, ApkInstallResult.BLOCKED))
    }
}
