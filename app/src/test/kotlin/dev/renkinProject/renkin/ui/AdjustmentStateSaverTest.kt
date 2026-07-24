package dev.renkinProject.renkin.ui

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.OutlineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustmentStateSaverTest {

    private val saveableScope = SaverScope { true }

    @Test
    fun saver_roundTripsKeyedState() {
        val original = populatedState()

        val saved = with(AdjustmentState.Saver) { saveableScope.save(original) }
        val restored = AdjustmentState.Saver.restore(requireNotNull(saved))

        assertState(restored)
    }

    @Test
    fun saver_restoresLegacyPositionalState() {
        val legacy = arrayListOf<Any>(
            1.25f, 3.5f, true, 1.4f,
            0.3f, true, -0.2f, 0.15f,
            true, IconShape.COOKIE.ordinal, false, Color.Magenta.toArgb(),
            1.2f, OutlineMode.RECOLOR.ordinal, 9f, Color.Cyan.toArgb(),
            true, true
        )

        val restored = AdjustmentState.Saver.restore(legacy)

        assertState(restored)
    }

    @Test
    fun materialYouPackSaver_roundTripsPaletteAndRelativeStrokeScale() {
        val original = MaterialYouPackAdjustmentState().apply {
            selectedScheme = 3
            customForeground = Color.Yellow
            customBackground = Color.Blue
            strokeScale = 1.4f
        }

        val saved = with(MaterialYouPackAdjustmentState.Saver) {
            saveableScope.save(original)
        }
        val restored = MaterialYouPackAdjustmentState.Saver.restore(requireNotNull(saved))

        assertNotNull(restored)
        restored!!
        assertEquals(3, restored.selectedScheme)
        assertEquals(Color.Yellow, restored.customForeground)
        assertEquals(Color.Blue, restored.customBackground)
        assertEquals(1.4f, restored.strokeScale)
    }

    @Test
    fun centeredLineWeightSlider_placesAndSnapsOneHundredPercentAtCenter() {
        assertEquals(0f, lineWeightToCenteredSlider(0.5f), 0.001f)
        assertEquals(1f, lineWeightToCenteredSlider(1f), 0.001f)
        assertEquals(2f, lineWeightToCenteredSlider(2f), 0.001f)
        assertEquals(0.5f, centeredSliderToLineWeight(0f), 0.001f)
        assertEquals(1f, centeredSliderToLineWeight(1.004f), 0.001f)
        assertEquals(2f, centeredSliderToLineWeight(2f), 0.001f)
    }

    private fun populatedState() = AdjustmentState().apply {
        edgeThreshold = 1.25f
        edgeSmoothing = 3.5f
        edgeContrast = true
        iconScale = 1.4f
        bgRemovalTolerance = 0.3f
        autoCenter = true
        iconOffsetX = -0.2f
        iconOffsetY = 0.15f
        colorizeFlat = true
        colorizeMonochrome = true
        colorizeInverse = true
        iconShape = IconShape.COOKIE
        shapeCrop = false
        shapeColor = Color.Magenta
        shapeScale = 1.2f
        outlineMode = OutlineMode.RECOLOR
        outlineWidth = 9f
        outlineColor = Color.Cyan
    }

    private fun assertState(state: AdjustmentState?) {
        assertNotNull(state)
        state!!
        assertEquals(1.25f, state.edgeThreshold)
        assertEquals(3.5f, state.edgeSmoothing)
        assertTrue(state.edgeContrast)
        assertEquals(1.4f, state.iconScale)
        assertEquals(0.3f, state.bgRemovalTolerance)
        assertTrue(state.autoCenter)
        assertEquals(-0.2f, state.iconOffsetX)
        assertEquals(0.15f, state.iconOffsetY)
        assertTrue(state.colorizeFlat)
        assertTrue(state.colorizeMonochrome)
        assertTrue(state.colorizeInverse)
        assertEquals(IconShape.COOKIE, state.iconShape)
        assertFalse(state.shapeCrop)
        assertEquals(Color.Magenta, state.shapeColor)
        assertEquals(1.2f, state.shapeScale)
        assertEquals(OutlineMode.RECOLOR, state.outlineMode)
        assertEquals(9f, state.outlineWidth)
        assertEquals(Color.Cyan, state.outlineColor)
    }
}
