package dev.renkinProject.renkin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ruler picker's maths. The dialog itself is Compose, but where a flick lands and how a value
 * reads are plain functions — and the flick is where the picker went wrong once already, sending
 * every throw to the far left regardless of direction.
 */
class RulerPickerTest {

    @Test
    fun flickRightMovesTowardsLowerSteps() {
        // Dragging right pulls the ruler left under the centre mark, so a rightward flick
        // continues that way. This is the regression that made every fling land on zero.
        val target = flingTarget(position = 50f, velocity = 1200f)

        assertTrue("expected a lower step, got $target", target < 50)
        assertTrue("expected a bounded carry, got $target", target > 40)
    }

    @Test
    fun flickLeftMovesTowardsHigherSteps() {
        val target = flingTarget(position = 50f, velocity = -1200f)

        assertTrue("expected a higher step, got $target", target > 50)
        assertTrue("expected a bounded carry, got $target", target < 60)
    }

    @Test
    fun theCarryIsCappedHoweverHardTheFlick() {
        val hard = flingTarget(position = 50f, velocity = -100_000f)

        // MAX_FLING_STEPS is 6: a throw must never spin through the whole range.
        assertEquals(56, hard)
    }

    @Test
    fun aSlowReleaseOnlySettlesOnTheNearestTick() {
        // Below the minimum velocity nothing is carried; the fractional position just rounds.
        assertEquals(50, flingTarget(position = 50.2f, velocity = 40f))
        assertEquals(51, flingTarget(position = 50.7f, velocity = -40f))
    }

    @Test
    fun theTargetStaysInsideTheRuler() {
        assertEquals(0, flingTarget(position = 2f, velocity = 5000f))
        assertEquals(100, flingTarget(position = 98f, velocity = -5000f))
    }

    @Test
    fun percentRulerReadsHundredthsAsWholePercent() {
        val spec = percentRuler()

        assertEquals(0.01f, spec.step)
        assertEquals("77%", spec.format(0.77f))
        assertEquals("100%", spec.format(1f))
    }

    @Test
    fun percentRulerCanDropItsSuffix() {
        assertEquals("12", percentRuler(suffix = "").format(0.12f))
    }

    @Test
    fun pixelAndCountRulersStepByWholeUnits() {
        assertEquals(1f, pixelRuler().step)
        assertEquals("6 px", pixelRuler().format(6f))
        assertEquals(1f, countRuler().step)
        assertEquals("8", countRuler().format(8f))
    }

    @Test
    fun decimalRulerReadsTenths() {
        val spec = decimalRuler()

        assertEquals(0.1f, spec.step)
        assertEquals("2.0", spec.format(2f))
        assertEquals("0.5", spec.format(0.5f))
    }

    @Test
    fun aSpecWithoutAUsableStepIsRejected() {
        // A zero or negative step would make the step count infinite or negative, and the ruler
        // would draw nothing while the value silently stopped following the drag.
        assertThrows(IllegalArgumentException::class.java) {
            RulerSpec(step = 0f) { "" }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RulerSpec(step = -1f) { "" }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RulerSpec(step = 1f, majorEvery = 0) { "" }
        }
    }

    /** The picker's own geometry: 10 dp ticks at 3x density, minimum flick 80 dp. */
    private fun flingTarget(position: Float, velocity: Float): Int = rulerFlingTarget(
        position = position,
        steps = 100,
        velocityPxPerSecond = velocity,
        stepPx = 30f,
        minFlingVelocityPx = 240f
    )
}
