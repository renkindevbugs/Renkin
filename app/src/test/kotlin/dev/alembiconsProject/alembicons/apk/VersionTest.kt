package dev.alembiconsProject.alembicons.apk

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The icon pack's version scheme drives F-Droid update continuity, so the round-trip between
 * versionName and internalVersionCode must stay stable. [Version] uses android TextUtils
 * (isDigitsOnly), so this runs under Robolectric rather than as a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class VersionTest {

    @Test
    fun parsesInternalVersionFromVersionName() {
        val version = Version(versionCode = 5L, versionName = "3.5.0")
        assertEquals(5L, version.versionCode)
        assertEquals("3.5.0", version.versionName)
        assertEquals(3, version.internalVersionCode)
    }

    @Test
    fun buildsVersionNameFromInternalVersion() {
        val version = Version(versionCode = 7L, internalVersionCode = 4)
        assertEquals(7L, version.versionCode)
        assertEquals(4, version.internalVersionCode)
        assertEquals("4.7.0", version.versionName)
    }

    @Test
    fun nonNumericVersionNameYieldsSentinel() {
        val version = Version(versionCode = 1L, versionName = "garbage")
        assertEquals(-1, version.internalVersionCode)
    }

    @Test
    fun emptyVersionNameYieldsSentinelWithoutCrashing() {
        // An empty name previously slipped past the digits check and crashed on toInt().
        assertEquals(-1, Version(versionCode = 1L, versionName = "").internalVersionCode)
    }

    @Test
    fun internalVersionSurvivesNameRoundTrip() {
        val name = Version(versionCode = 9L, internalVersionCode = 2).versionName
        val reparsed = Version(versionCode = 9L, versionName = name)
        assertEquals(2, reparsed.internalVersionCode)
    }
}
