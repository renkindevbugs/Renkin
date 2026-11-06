package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.data.RawDynamicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DynamicClockPreviewTest {

    // Dynamic Icons (com.akbon.myd) google_android_clock: drawn at 10:10:30, rotate
    // from/to degrees hour 300..5300, minute 60..60060, second 180..6180 over 10000 levels.
    private val clock = RawDynamicClock(
        drawableLink = "google_android_clock",
        defaultHour = "10",
        defaultMinute = "10",
        hourLayerIndex = "0",
        minuteLayerIndex = "1",
        defaultSecond = "30",
        secondLayerIndex = "2"
    )

    private fun angle(fromDegrees: Float, toDegrees: Float, level: Int): Float =
        (fromDegrees + (toDegrees - fromDegrees) * level / 10000f) % 360f

    private fun anglesAt(hour: Int, minute: Int): List<Float> {
        val (hourLevel, minuteLevel, secondLevel) = clock.handLevels(hour, minute)
        return listOf(
            angle(300f, 5300f, hourLevel),
            angle(60f, 60060f, minuteLevel),
            angle(180f, 6180f, secondLevel)
        )
    }

    @Test
    fun handLevels_pointEveryHandAtTheRequestedTime() {
        assertEquals(listOf(195f, 180f, 0f), anglesAt(6, 30))
        assertEquals(listOf(292.5f, 270f, 0f), anglesAt(9, 45))
        assertEquals(listOf(0f, 0f, 0f), anglesAt(12, 0))
        assertEquals(listOf(90f, 0f, 0f), anglesAt(15, 0))
    }

    @Test
    fun handLayerIndices_keepsAnAbsentSecondHand() {
        val withoutSeconds = clock.copy(secondLayerIndex = "-1")

        assertEquals(listOf(0, 1, -1), withoutSeconds.handLayerIndices(layerCount = 3))
    }

    @Test
    fun handLayerIndices_rejectsUnusableDeclarations() {
        assertNull(clock.copy(minuteLayerIndex = "0").handLayerIndices(layerCount = 3))
        assertNull(clock.handLayerIndices(layerCount = 2))
        assertNull(clock.copy(defaultMinute = "60").handLayerIndices(layerCount = 3))
        assertNull(clock.copy(hourLayerIndex = "x").handLayerIndices(layerCount = 3))
    }
}
