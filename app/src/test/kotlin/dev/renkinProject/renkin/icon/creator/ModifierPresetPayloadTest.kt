package dev.renkinProject.renkin.icon.creator

import android.graphics.Color
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModifierPresetPayloadTest {

    private fun options() = GenerationOptions(
        primarySource = Source.APPLICATION_ICON,
        primaryImageEdit = ImageEdit.NONE,
        primaryTextType = TextType.FULL_NAME,
        primaryIconPack = "",
        color = Color.BLACK,
        bgColor = Color.TRANSPARENT,
        vector = false,
        materialYou = false,
        themed = false,
        override = true,
        iconOffsetX = 0.2f,
        iconOffsetY = -0.1f
    )

    @Test
    fun roundTrip_preservesEveryPortableGroup() {
        val gradient = ColorizerStyle(
            mode = ColorizerMode.GRADIENT,
            gradientType = GradientType.RADIAL,
            firstColor = Color.RED,
            gradientStops = listOf(Color.BLUE, Color.GREEN),
            gradientPositions = listOf(0f, 0.4f, 1f),
            gradientAngle = 35f,
            flat = true,
            monochrome = true,
            inverse = true
        )
        val original = ModifierPresetPayload(
            effect = ModifierPresetEffect(ImageEdit.COLORIZE, 1.5f, 3f, true, 0.2f, gradient),
            iconScale = 0.72f,
            shape = ModifierPresetShape(IconShape.PEBBLE, false, 1.2f, gradient),
            outline = ModifierPresetOutline(OutlineMode.ADD, 9f, gradient)
        )

        assertEquals(original, decodeModifierPreset(encodeModifierPreset(original)))
    }

    @Test
    fun decode_emptyDocumentReturnsNull_andDamagedNumbersAreSafe() {
        assertNull(decodeModifierPreset("v=1"))

        val decoded = checkNotNull(
            decodeModifierPreset(
                """
                v=1
                effect=1
                effect.edgeThreshold=NaN
                effect.edgeSmoothing=99
                effect.bgTolerance=-5
                iconScale=Infinity
                shape=1
                shape.scale=8
                outline=1
                outline.width=-2
                """.trimIndent()
            )
        )

        assertEquals(2.5f, decoded.effect?.edgeThreshold)
        assertEquals(4f, decoded.effect?.edgeSmoothing)
        assertEquals(0f, decoded.effect?.backgroundTolerance)
        assertNull(decoded.iconScale)
        assertEquals(1.5f, decoded.shape?.scale)
        assertEquals(1f, decoded.outline?.width)
    }

    @Test
    fun applyPreset_changesIncludedGroupsAndPreservesIconSpecificOptions() {
        val style = ColorizerStyle(firstColor = Color.MAGENTA, flat = true)
        val result = options().withModifierPreset(
            ModifierPresetPayload(
                effect = ModifierPresetEffect(ImageEdit.COLORIZE, 1f, 2f, true, 0.3f, style),
                iconScale = 0.8f,
                outline = ModifierPresetOutline(OutlineMode.RECOLOR, 4f, style)
            )
        )

        assertEquals(ImageEdit.COLORIZE, result.primaryImageEdit)
        assertEquals(Color.MAGENTA, result.color)
        assertEquals(0.8f, result.iconScale)
        assertEquals(OutlineMode.RECOLOR, result.outlineMode)
        assertEquals(0.2f, result.iconOffsetX)
        assertEquals(-0.1f, result.iconOffsetY)
    }
}
