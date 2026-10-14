package dev.renkinProject.renkin

import dev.renkinProject.renkin.apk.ApkInstallAttempt
import dev.renkinProject.renkin.apk.ApkInstallBackend
import dev.renkinProject.renkin.apk.ApkInstallOutcome
import dev.renkinProject.renkin.apk.ApkInstallResult
import dev.renkinProject.renkin.data.InstallMethod
import org.junit.Assert.assertEquals
import org.junit.Test

class InstallSuccessMessageTest {
    @Test
    fun successfulExternalInstallDistinguishesFirstInstallAndUpdate() {
        val outcome = ApkInstallOutcome(ApkInstallResult.SUCCESS,
            requestedMethod = InstallMethod.EXTERNAL,
            attempts = listOf(ApkInstallAttempt(ApkInstallBackend.EXTERNAL, ApkInstallResult.SUCCESS,
                installerLabel = "Test installer")))
        assertEquals(R.string.buildInstalledToast, installSuccessMessage(outcome, wasUpdate = false))
        assertEquals(R.string.buildUpdatedToast, installSuccessMessage(outcome, wasUpdate = true))
    }

    @Test
    fun shizukuFallbackUsesTheSuccessfulBackendRatherThanTheRequestedMethod() {
        val outcome = ApkInstallOutcome(ApkInstallResult.SUCCESS,
            requestedMethod = InstallMethod.SHIZUKU,
            attempts = listOf(
                ApkInstallAttempt(ApkInstallBackend.SHIZUKU, ApkInstallResult.BLOCKED),
                ApkInstallAttempt(ApkInstallBackend.SYSTEM_SESSION, ApkInstallResult.SUCCESS)))
        assertEquals(R.string.buildInstalledShizukuFallbackToast, installSuccessMessage(outcome, false))
        assertEquals(R.string.buildUpdatedShizukuFallbackToast, installSuccessMessage(outcome, true))
    }

    @Test
    fun lateVerifiedShizukuSuccessDoesNotClaimSystemFallback() {
        val outcome = ApkInstallOutcome(ApkInstallResult.SUCCESS,
            requestedMethod = InstallMethod.SHIZUKU,
            attempts = listOf(
                ApkInstallAttempt(ApkInstallBackend.SHIZUKU, ApkInstallResult.FAILED),
                ApkInstallAttempt(ApkInstallBackend.SHIZUKU, ApkInstallResult.SUCCESS)))
        assertEquals(R.string.buildUpdatedToast, installSuccessMessage(outcome, true))
    }
}
