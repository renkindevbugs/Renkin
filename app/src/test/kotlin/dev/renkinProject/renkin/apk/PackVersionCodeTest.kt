package dev.renkinProject.renkin.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackVersionCodeTest {
    private val epoch = 1_577_836_800_000L
    private val now = epoch + 3_593_418L * 60_000L

    @Test
    fun aPackAndroidStillRecordsButThisAppCannotSeeIsNotRebuiltAsVersionOne() {
        assertEquals(3_593_418L, nextPackVersionCode(installedVersionCode = 0, nowMillis = now))
    }

    @Test
    fun rebuildsWithinTheSameMinuteStillGoUp() {
        assertEquals(3_593_419L, nextPackVersionCode(installedVersionCode = 3_593_418L, nowMillis = now))
    }

    @Test
    fun anInstalledVersionAheadOfTheClockIsStillExceeded() {
        assertEquals(9_000_001L, nextPackVersionCode(installedVersionCode = 9_000_000L, nowMillis = now))
    }

    @Test
    fun theVersionFitsTheManifestIntForCenturies() {
        val year3000 = epoch + 980L * 365 * 24 * 60 * 60_000L
        assertTrue(nextPackVersionCode(installedVersionCode = 0, nowMillis = year3000) < Int.MAX_VALUE)
    }
}
