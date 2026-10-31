package dev.renkinProject.renkin.feature.launch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class LaunchFireTest {

    @Test
    fun gateDecidesPlaybackPerLaunchKind() {
        assertEquals(LaunchFirePlayback.Play, LaunchFireGate.decide(firstInProcess = true, restoredAfterProcessDeath = false, animatorScale = 1f))
        assertEquals(LaunchFirePlayback.FadeOnly, LaunchFireGate.decide(firstInProcess = false, restoredAfterProcessDeath = false, animatorScale = 1f))
        assertEquals(LaunchFirePlayback.FadeOnly, LaunchFireGate.decide(firstInProcess = true, restoredAfterProcessDeath = true, animatorScale = 1f))
        assertEquals(LaunchFirePlayback.None, LaunchFireGate.decide(firstInProcess = true, restoredAfterProcessDeath = false, animatorScale = 0f))
    }

    @Test
    fun timelineStartsAtTheSplashAndEndsAtRest() {
        assertTrue(LaunchFireTimeline.front(0f) < 0f)
        assertTrue(LaunchFireTimeline.front(1f) > 1f)
        assertEquals(0f, LaunchFireTimeline.flame(0f), 0f)
        assertEquals(0f, LaunchFireTimeline.flame(1f), 0f)
        assertEquals(0f, LaunchFireTimeline.grow(0f), 0f)
        assertEquals(1f, LaunchFireTimeline.grow(1f), 1e-4f)
        var previous = LaunchFireTimeline.front(0f)
        for (step in 1..100) {
            val front = LaunchFireTimeline.front(step / 100f)
            assertTrue(front >= previous)
            previous = front
        }
    }

    @Test
    fun maskIsNullWithoutGold() {
        assertNull(buildLaunchFireMask(IntArray(64 * 64) { PLUM }, 64, 64))
    }

    @Test
    fun maskCropsTheSymbolAndBurnsFromTheSpike() {
        val mask = buildLaunchFireMask(ringImage(), SIZE, SIZE)
        assertNotNull(mask)
        mask!!
        assertEquals(SIZE, mask.sourceSize)
        assertTrue(mask.width < SIZE && mask.height < SIZE)
        assertTrue(mask.cropLeft > 0 && mask.cropTop > 0)
        assertTrue(mask.burnTime.all { it in 0f..1f })
        for (n in 1 until mask.frontOrder.size) {
            assertTrue(mask.burnTime[mask.frontOrder[n - 1]] <= mask.burnTime[mask.frontOrder[n]])
        }
        val first = mask.frontOrder.first()
        val last = mask.frontOrder.last()
        assertTrue("ignites top-right", first % mask.width > mask.width / 2 && first / mask.width < mask.height / 2)
        assertTrue("ends away from the spike", last % mask.width < mask.width / 2 || last / mask.width > mask.height / 2)
    }

    @Test
    fun engineIsDeterministicAndCapped() {
        val mask = buildLaunchFireMask(ringImage(), SIZE, SIZE)!!
        val first = LaunchFireEngine(mask, capacity = 12)
        val second = LaunchFireEngine(mask, capacity = 12)
        repeat(40) {
            first.step()
            second.step()
            assertTrue(first.count <= 12)
        }
        assertEquals(first.count, second.count)
        assertArrayEquals(first.x, second.x, 0f)
        assertArrayEquals(first.y, second.y, 0f)
    }

    @Test
    fun fireStaysOnTheSymbolAndNeverBelowIt() {
        val mask = buildLaunchFireMask(ringImage(), SIZE, SIZE)!!
        val engine = LaunchFireEngine(mask, capacity = 160)
        var sawFire = false
        while (!engine.finished) {
            engine.step()
            for (i in 0 until engine.count) {
                assertTrue(engine.y[i] <= mask.height.toFloat())
                sawFire = true
            }
        }
        assertTrue(sawFire)
    }

    @Test
    fun symbolStartsAsTheSplashGoldAndRepaintsOnceAtRest() {
        val mask = buildLaunchFireMask(ringImage(), SIZE, SIZE)!!
        val engine = LaunchFireEngine(mask, capacity = 16)
        assertTrue(engine.paintSymbol())
        val solid = mask.coverage.indices.first { mask.coverage[it] >= 0.99f }
        assertEquals(LaunchGoldRgb, engine.pixels[solid] and 0xFFFFFF)

        while (!engine.finished) engine.step()
        assertTrue(engine.paintSymbol())
        assertFalse(engine.paintSymbol())
        assertTrue(engine.pixels[solid] and 0xFFFFFF != LaunchGoldRgb)
    }

    private companion object {
        const val SIZE = 128
        const val PLUM = 0xFF1E1228.toInt()
        const val GOLD = 0xFFD5B15B.toInt()
        const val LaunchGoldRgb = 0xD5B15B

        /** A gold ring with a spike at the top right, on the plum square like the real foreground. */
        fun ringImage(): IntArray = IntArray(SIZE * SIZE) { index ->
            val x = index % SIZE
            val y = index / SIZE
            val ring = hypot(x - 64.0, y - 70.0) in 24.0..30.0
            val spike = x in 84..96 && y in 24..52 && (x - 84) >= (52 - y) / 3
            if (ring || spike) GOLD else PLUM
        }
    }
}
