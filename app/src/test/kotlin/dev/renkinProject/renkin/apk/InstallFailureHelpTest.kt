package dev.renkinProject.renkin.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallFailureHelpTest {
    @Test
    fun hyperOsIsolationIsRecognisedOnAnyBrand() {
        assertEquals(InstallFailureHelp.HYPEROS_ISOLATION, installFailureHelp(ApkInstallResult.FAILED,
            "Session failed: INSTALL_FAILED_HYPEROS_ISOLATION_VIOLATION", "Xiaomi"))
    }

    @Test
    fun userRestrictionPointsXiaomiFamilyToInstallViaUsb() {
        for (brand in listOf("Xiaomi", "Redmi", "POCO")) {
            assertEquals(brand, InstallFailureHelp.XIAOMI_INSTALL_VIA_USB,
                installFailureHelp(ApkInstallResult.BLOCKED, "INSTALL_FAILED_USER_RESTRICTED: Install canceled by user", brand))
        }
        assertEquals(InstallFailureHelp.USER_RESTRICTION,
            installFailureHelp(ApkInstallResult.BLOCKED, "blocked by a user restriction", "Google"))
    }

    @Test
    fun playProtectIsRecognisedFromVerificationFailures() {
        assertEquals(InstallFailureHelp.PLAY_PROTECT,
            installFailureHelp(ApkInstallResult.FAILED, "INSTALL_FAILED_VERIFICATION_FAILURE", "OnePlus"))
    }

    @Test
    fun samsungAutoBlockerNeedsAnActualBlockedResult() {
        assertEquals(InstallFailureHelp.SAMSUNG_AUTO_BLOCKER,
            installFailureHelp(ApkInstallResult.BLOCKED, "Installation blocked", "samsung"))
        assertNull(installFailureHelp(ApkInstallResult.FAILED, "Installation blocked", "samsung"))
    }

    @Test
    fun unrecognisedFailuresGetNoAdviceEvenOnKnownBrands() {
        assertNull(installFailureHelp(ApkInstallResult.FAILED, "Session 12 is dead.", "Xiaomi"))
        assertNull(installFailureHelp(ApkInstallResult.BLOCKED, "Installation blocked", "OnePlus"))
        assertNull(installFailureHelp(ApkInstallResult.STORAGE, "INSTALL_FAILED_INSUFFICIENT_STORAGE", "samsung"))
    }
}
