package dev.renkinProject.renkin.icon.creator

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorizerStyleTest {

    @Test
    fun normalizeGradientAngleClampsToRange() {
        assertEquals(0f, normalizeGradientAngle(-30f), 0f)
        assertEquals(360f, normalizeGradientAngle(400f), 0f)
        assertEquals(137f, normalizeGradientAngle(137f), 0f)
    }

    @Test
    fun snapGradientAnglePullsInNearbyMultiplesOf45() {
        assertEquals(90f, snapGradientAngle(92f), 0f)
        assertEquals(90f, snapGradientAngle(85f), 0f)
        assertEquals(135f, snapGradientAngle(135f), 0f)
    }

    @Test
    fun snapGradientAngleLeavesFreeAnglesUntouched() {
        assertEquals(100f, snapGradientAngle(100f), 0f)
        assertEquals(37f, snapGradientAngle(37f), 0f)
    }

    @Test
    fun snapGradientAngleWrapsAroundZero() {
        // 358° is inside the magnet zone of 360°, which must read as 0° rather than a full turn.
        assertEquals(0f, snapGradientAngle(358f), 0f)
        assertEquals(0f, snapGradientAngle(-2f), 0f)
    }
}
