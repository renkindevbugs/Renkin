package dev.renkinProject.renkin.ui

import android.graphics.Color
import dev.renkinProject.renkin.data.ColorPreset
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.encodeColorizerStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When the bookmark lights up, and how the saved-colour list is searched and ordered. Both drive
 * what the user sees the moment they change a stop, so both are worth pinning down.
 */
class SavedColorMatchingTest {

    private val gradient = ColorizerStyle(
        mode = ColorizerMode.GRADIENT,
        firstColor = Color.RED,
        gradientStops = listOf(Color.BLUE),
        gradientPositions = listOf(0f, 1f)
    )

    private fun presetOf(id: Long, style: ColorizerStyle, name: String = "Saved", created: Long = id) =
        ColorPreset(id = id, name = name, style = encodeColorizerStyle(style), createdAt = created)

    @Test
    fun matchesTheSameColoursAtTheSamePositions() {
        val presets = listOf(presetOf(1, gradient))

        assertEquals(1L, savedPresetMatching(presets, gradient)?.id)
    }

    @Test
    fun angleAndTypeDescribeTheSameColours() {
        val presets = listOf(presetOf(1, gradient))

        assertNotNull(savedPresetMatching(presets, gradient.copy(gradientAngle = 180f)))
        assertNotNull(
            savedPresetMatching(presets, gradient.copy(gradientType = GradientType.RADIAL))
        )
    }

    @Test
    fun repaintingOrMovingAStopIsADifferentColour() {
        val presets = listOf(presetOf(1, gradient))

        assertNull(savedPresetMatching(presets, gradient.copy(firstColor = Color.GREEN)))
        assertNull(savedPresetMatching(presets, gradient.copy(gradientPositions = listOf(0f, 0.5f))))
    }

    @Test
    fun anEvenSpreadMatchesAGradientSavedWithoutPositions() {
        // Presets saved before stop positions existed carry none, and they paint the even spread.
        val presets = listOf(presetOf(1, gradient.copy(gradientPositions = emptyList())))

        assertNotNull(savedPresetMatching(presets, gradient))
    }

    @Test
    fun singleColoursDoNotMatchGradientsOfTheSameColour() {
        val single = ColorizerStyle(mode = ColorizerMode.SINGLE_COLOR, firstColor = Color.RED)
        val presets = listOf(presetOf(1, single))

        assertNotNull(savedPresetMatching(presets, single))
        assertNull(savedPresetMatching(presets, gradient))
    }

    @Test
    fun sortingAndSearchingTheLibrary() {
        val presets = listOf(
            presetOf(1, gradient, name = "Zephyr", created = 300),
            presetOf(2, gradient, name = "alpha", created = 100),
            presetOf(3, gradient, name = "Mid", created = 200)
        )

        assertEquals(
            listOf("Zephyr", "Mid", "alpha"),
            sortedColorPresets(presets, "", ColorPresetSort.NEWEST).map { it.name }
        )
        assertEquals(
            listOf("alpha", "Mid", "Zephyr"),
            sortedColorPresets(presets, "", ColorPresetSort.OLDEST).map { it.name }
        )
        // Case-insensitive, or "alpha" would sort after every capitalised name.
        assertEquals(
            listOf("alpha", "Mid", "Zephyr"),
            sortedColorPresets(presets, "", ColorPresetSort.NAME).map { it.name }
        )
        assertEquals(
            listOf("Mid"),
            sortedColorPresets(presets, " mid ", ColorPresetSort.NAME).map { it.name }
        )
    }
}
