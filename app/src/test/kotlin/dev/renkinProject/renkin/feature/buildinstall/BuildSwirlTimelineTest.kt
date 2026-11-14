package dev.renkinProject.renkin.feature.buildinstall

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stages of the build animation must hand over in order, whatever their tuned durations are. */
class BuildSwirlTimelineTest {
    private val moments = generateSequence(0f) { it + 10f }.takeWhile { it <= BuildSwirl.TOTAL }.toList()

    @Test
    fun everyStageStaysInRangeAndStartsAndEndsWhereItShould() {
        moments.forEach { t ->
            listOf(
                BuildSwirl.chrome(t), BuildSwirl.home(t), BuildSwirl.arms(t), BuildSwirl.core(t),
                BuildSwirl.pulse(t), BuildSwirl.pop(t), BuildSwirl.pillToButton(t), BuildSwirl.symbolGlow(t)
            ).forEach { value -> assertTrue("$value at $t", value in 0f..1f) }
        }
        assertEquals(1f, BuildSwirl.chrome(0f), 0f)
        assertEquals(0f, BuildSwirl.home(0f), 0f)
        assertEquals(1f, BuildSwirl.pillToButton(0f), 0f)
        assertEquals(1f, BuildSwirl.home(BuildSwirl.TOTAL), 0f)
        assertEquals(0f, BuildSwirl.arms(BuildSwirl.TOTAL), 0f)
        assertEquals(0f, BuildSwirl.core(BuildSwirl.TOTAL), 0f)
        assertEquals(1f, BuildSwirl.pop(BuildSwirl.TOTAL), 0f)
    }

    @Test
    fun galaxyOnlySpinsForwardAndRestsUprightBeforeTheSymbolSharpens() {
        moments.zipWithNext { earlier, later -> assertTrue(BuildSwirl.angle(later) >= BuildSwirl.angle(earlier)) }
        val pulseStart = moments.first { BuildSwirl.pulse(it) > 0f }
        assertEquals(BuildSwirl.restAngle, BuildSwirl.angle(pulseStart), 0.5f)
        assertEquals(BuildSwirl.restAngle, BuildSwirl.angle(BuildSwirl.TOTAL), 0f)
    }

    @Test
    fun previewChromeIsGoneBeforeTheHomeScreenSurfaces() {
        moments.filter { BuildSwirl.home(it) > 0f }.forEach { t -> assertEquals(0f, BuildSwirl.chrome(t), 0f) }
    }

    @Test
    fun buildButtonReachesTheFabWhileTheHomeScreenIsStillFadingIn() {
        val arrival = moments.first { BuildSwirl.pillToButton(it) == 0f }
        assertTrue(BuildSwirl.home(arrival) > 0f)
        assertTrue(BuildSwirl.home(arrival) < 1f)
    }

    @Test
    fun everyIconFragmentIsAbsorbedBeforeTheSymbolSharpens() {
        val pulseStart = moments.first { BuildSwirl.pulse(it) > 0f }
        val grid = 9
        for (row in 0 until grid) for (column in 0 until grid) {
            assertTrue(BuildSwirl.fragmentProgress(pulseStart, row, column, grid, seed = row * grid + column) >= 1f)
        }
    }

    @Test
    fun symbolAssemblesFromTheCentreOutAndIsCompleteWhenThePulseStarts() {
        val pulseStart = moments.first { BuildSwirl.pulse(it) > 0f }
        assertTrue(BuildSwirl.formProgress(pulseStart, order = 1f) >= 1f)
        moments.forEach { t -> assertTrue(BuildSwirl.formProgress(t, order = 0f) >= BuildSwirl.formProgress(t, order = 1f)) }
    }

    @Test
    fun symbolIsFullySharpBeforeItPopsIntoTheFab() {
        moments.filter { BuildSwirl.pop(it) > 0f }.forEach { t -> assertEquals(1f, BuildSwirl.pulse(t), 0f) }
    }

    @Test
    fun fragmentSpiralsFromItsPlaceIntoTheCore() {
        val start = Offset(40f, 300f)
        val centre = Offset(500f, 900f)
        assertEquals(start.x, BuildSwirl.dustPosition(start, centre, 0f).x, 0.01f)
        assertEquals(start.y, BuildSwirl.dustPosition(start, centre, 0f).y, 0.01f)
        assertEquals(centre.x, BuildSwirl.dustPosition(start, centre, 1f).x, 0.01f)
        assertEquals(centre.y, BuildSwirl.dustPosition(start, centre, 1f).y, 0.01f)
    }
}
