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
        source = IconSourceOptions(
            primarySource = Source.APPLICATION_ICON,
            primaryTextType = TextType.FULL_NAME,
            primaryIconPack = ""
        ),
        export = IconExportOptions(
            vector = false,
            materialYou = false,
            themed = false,
            background = ColorFill(Color.TRANSPARENT)
        ),
        override = true,
        modifiers = ModifierStack(
            effect = EffectModifier(imageEdit = ImageEdit.NONE, colorizer = ColorizerStyle(firstColor = Color.BLACK)),
            transform = TransformModifier(offsetX = 0.2f, offsetY = -0.1f),
        )
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
            lighten = true,
            monochrome = true,
            inverse = true
        )
        val original = ModifierPresetPayload(
            effect = ModifierPresetEffect(ImageEdit.COLORIZE, 1.5f, 3f, true, 0.2f, gradient),
            iconScale = 0.72f,
            shape = ModifierPresetShape(IconShape.PEBBLE, false, 1.2f, gradient),
            outline = ModifierPresetOutline(OutlineMode.ADD, 9f, gradient),
            shadow = ModifierPresetShadow(true, 20f, 5f, 270f, true, gradient, 0.6f)
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

        assertEquals(ImageEdit.COLORIZE, result.modifiers.effect.imageEdit)
        assertEquals(Color.MAGENTA, result.modifiers.effect.colorizer.firstColor)
        assertEquals(0.8f, result.modifiers.transform.scale)
        assertEquals(OutlineMode.RECOLOR, result.modifiers.outline?.mode)
        assertEquals(0.2f, result.modifiers.transform.offsetX)
        assertEquals(-0.1f, result.modifiers.transform.offsetY)
    }

    @Test
    fun platePresetKeepsTheGeneratedMaterialYouBackgroundColour() {
        val plate = ColorizerStyle(firstColor = Color.WHITE)
        val payload = ModifierPresetPayload(shape = ModifierPresetShape(IconShape.COOKIE, false, 1f, plate))
        val materialYou = options()
            .withSource { copy(applicationIconVariant = ApplicationIconVariant.MATERIAL_YOU) }
            .withExport { copy(background = ColorFill(Color.BLACK)) }

        val generated = materialYou.withModifierPreset(payload).export.background
        val ordinary = options().withModifierPreset(payload).export.background

        assertEquals(ColorFill(Color.BLACK, plate), generated)
        assertEquals(ColorFill(Color.WHITE, plate), ordinary)
    }

    @Test
    fun decode_shadowWithoutStyleFallsBackToBlackAndDefaults() {
        val shadow = checkNotNull(
            decodeModifierPreset("v=1\nshadow=1\nshadow.enabled=true")?.shadow
        )

        assertEquals(
            ModifierPresetShadow(
                enabled = true,
                blur = SHADOW_BLUR_DEFAULT,
                distance = SHADOW_DISTANCE_DEFAULT,
                angle = SHADOW_ANGLE_DEFAULT,
                allDirections = false,
                style = ColorizerStyle(firstColor = Color.BLACK),
                opacity = SHADOW_OPACITY_DEFAULT
            ),
            shadow
        )
    }
}
