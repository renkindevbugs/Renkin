package dev.renkinProject.renkin.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.IconShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalModifierStateSaverTest {

    private val saveableScope = SaverScope { true }

    @Test
    fun saver_roundTripsWholeColorStyles() {
        val original = populatedState()

        val saved = with(GlobalModifierState.Saver) { saveableScope.save(original) }
        val restored = GlobalModifierState.Saver.restore(requireNotNull(saved))

        assertNotNull(restored)
        assertTrue(requireNotNull(restored).initialized)
        assertEquals(original.snapshot(), restored.snapshot())
    }

    @Test
    fun baselineSaver_roundTripsDirtyCheckSnapshot() {
        val original = populatedState().snapshot()
        val baseline = mutableStateOf<GlobalModifierSnapshot?>(original)

        val saved = with(GlobalModifierState.BaselineSaver) { saveableScope.save(baseline) }
        val restored = GlobalModifierState.BaselineSaver.restore(requireNotNull(saved))

        assertEquals(original, restored?.value)
    }

    @Test
    fun saver_restoresLegacyPositionalSnapshot() {
        val legacy = listOf(
            IconShape.COOKIE.ordinal,
            false,
            125,
            Color.Magenta.toArgb(),
            80,
            true,
            9,
            Color.Cyan.toArgb(),
            true,
            Color.Yellow.toArgb(),
            true,
            false,
            true,
            true,
            false,
            true,
            true,
            ColorizerMode.GRADIENT.ordinal,
            Color.Red.toArgb(),
            137,
            GradientType.RADIAL.ordinal,
            ColorizerMode.GRADIENT.ordinal,
            GradientType.LINEAR.ordinal,
            Color.Blue.toArgb(),
            45,
            "0.0,0.7",
            "0.0,0.4",
            ColorizerMode.GRADIENT.ordinal,
            GradientType.RADIAL.ordinal,
            Color.Green.toArgb(),
            "0.0,0.6",
            210
        ).joinToString("|")

        val restored = GlobalModifierState.Saver.restore(legacy)

        assertNotNull(restored)
        restored!!
        assertEquals(IconShape.COOKIE, restored.shape)
        assertEquals(125, restored.snapshot().shapeScalePercent)
        assertEquals(80, restored.snapshot().iconScalePercent)
        assertEquals(Color.Magenta.toArgb(), restored.shapeStyle.firstColor)
        assertEquals(ColorizerMode.GRADIENT, restored.shapeStyle.mode)
        assertEquals(GradientType.RADIAL, restored.shapeStyle.gradientType)
        assertEquals(listOf(Color.Green.toArgb()), restored.shapeStyle.gradientStops)
        assertEquals(listOf(0f, 0.6f), restored.shapeStyle.gradientPositions)
        assertEquals(210f, restored.shapeStyle.gradientAngle)
        assertEquals(Color.Cyan.toArgb(), restored.outlineStyle.firstColor)
        assertEquals(listOf(Color.Blue.toArgb()), restored.outlineStyle.gradientStops)
        assertEquals(listOf(0f, 0.4f), restored.outlineStyle.gradientPositions)
        assertEquals(Color.Yellow.toArgb(), restored.colorizerStyle.firstColor)
        assertEquals(listOf(Color.Red.toArgb()), restored.colorizerStyle.gradientStops)
        assertEquals(listOf(0f, 0.7f), restored.colorizerStyle.gradientPositions)
        assertTrue(restored.colorizerStyle.flat)
        assertTrue(restored.colorizerStyle.monochrome)
        assertTrue(restored.colorizerStyle.inverse)
    }

    private fun populatedState() = GlobalModifierState().apply {
        shape = IconShape.COOKIE
        shapeCrop = false
        shapeScale = 1.25f
        shapeStyle = ColorizerStyle(
            mode = ColorizerMode.GRADIENT,
            gradientType = GradientType.RADIAL,
            firstColor = Color.Magenta.toArgb(),
            gradientStops = listOf(Color.Green.toArgb()),
            gradientPositions = listOf(0f, 0.6f),
            gradientAngle = 210f
        )
        iconScale = 0.8f
        outlineAdd = true
        outlineWidth = 9f
        outlineStyle = ColorizerStyle(
            mode = ColorizerMode.GRADIENT,
            firstColor = Color.Cyan.toArgb(),
            gradientStops = listOf(Color.Blue.toArgb()),
            gradientPositions = listOf(0f, 0.4f),
            gradientAngle = 45f
        )
        colorize = true
        colorizerStyle = ColorizerStyle(
            mode = ColorizerMode.GRADIENT,
            gradientType = GradientType.RADIAL,
            firstColor = Color.Yellow.toArgb(),
            gradientStops = listOf(Color.Red.toArgb()),
            gradientPositions = listOf(0f, 0.7f),
            gradientAngle = 137f,
            flat = true,
            monochrome = true,
            inverse = true
        )
        applyGenerated = false
        applyExisting = true
        applyCustom = true
        includeEmpty = true
    }
}
