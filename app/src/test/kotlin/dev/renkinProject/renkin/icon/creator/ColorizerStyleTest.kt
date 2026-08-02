package dev.renkinProject.renkin.icon.creator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Literals rather than android.graphics.Color: these run as plain JVM tests, where the android.jar
// stubs throw.
private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
private const val GREY = 0xFF7F7F7F.toInt()
private val BlackAndWhite = listOf(WHITE, BLACK)

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
    fun evenGradientPositionsSpansTheWholeTrack() {
        assertEquals(listOf(0f, 1f), evenGradientPositions(2))
        assertEquals(listOf(0f, 0.5f, 1f), evenGradientPositions(3))
        assertEquals(listOf(0f), evenGradientPositions(1))
        assertEquals(emptyList<Float>(), evenGradientPositions(0))
    }

    @Test
    fun clampedGradientPositionsPullsCrossedStopsUpInsteadOfReordering() {
        // A stop dragged past its neighbour must stay in its slot and paint a hard edge — the
        // list order is what the rows and handles are built on.
        assertEquals(listOf(0.6f, 0.6f, 1f), clampedGradientPositions(listOf(0.6f, 0.2f, 1f)))
        assertEquals(listOf(0f, 0.4f, 1f), clampedGradientPositions(listOf(0f, 0.4f, 1f)))
        assertEquals(listOf(0f, 1f, 1f), clampedGradientPositions(listOf(-1f, 2f, 0.5f)))
    }

    @Test
    fun shaderGradientPositionsOnlyDescribesEveryStop() {
        assertNull(shaderGradientPositions(listOf(1, 2), emptyList()))
        assertNull(shaderGradientPositions(listOf(1, 2), listOf(0f)))
        // One colour is not a gradient; the shader duplicates it and wants no positions.
        assertNull(shaderGradientPositions(listOf(1), listOf(0f)))
        assertArrayEquals(
            floatArrayOf(0f, 0.5f),
            shaderGradientPositions(listOf(1, 2), listOf(0f, 0.5f)),
            0f
        )
    }

    @Test
    fun gradientColorAtMixesTheColourAlreadyPainted() {
        val positions = listOf(0f, 1f)

        assertEquals(WHITE, gradientColorAt(BlackAndWhite, positions, 0f))
        assertEquals(BLACK, gradientColorAt(BlackAndWhite, positions, 1f))
        // Halfway between white and black is grey, not either neighbour.
        assertEquals(GREY, gradientColorAt(BlackAndWhite, positions, 0.5f))
    }

    @Test
    fun gradientColorAtHonoursPositionsAndEdges() {
        // Both stops crowded into the first half: everything past 0.5 is already solid black.
        val positions = listOf(0f, 0.5f)

        assertEquals(GREY, gradientColorAt(BlackAndWhite, positions, 0.25f))
        assertEquals(BLACK, gradientColorAt(BlackAndWhite, positions, 0.9f))
    }

    @Test
    fun gradientColorAtFallsBackToAnEvenSpreadWhenPositionsAreMissing() {
        assertEquals(GREY, gradientColorAt(BlackAndWhite, emptyList(), 0.5f))
    }

    @Test
    fun gradientColorAtKeepsAlpha() {
        val transparentWhite = 0x00FFFFFF
        val mixed = gradientColorAt(listOf(transparentWhite, WHITE), listOf(0f, 1f), 0.5f)

        assertEquals(127, (mixed ushr 24) and 0xFF)
        assertEquals(0xFFFFFF, mixed and 0xFFFFFF)
    }

    @Test
    fun snapGradientAngleWrapsAroundZero() {
        // 358° is inside the magnet zone of 360°, which must read as 0° rather than a full turn.
        assertEquals(0f, snapGradientAngle(358f), 0f)
        assertEquals(0f, snapGradientAngle(-2f), 0f)
    }
}
