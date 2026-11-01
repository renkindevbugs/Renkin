package dev.renkinProject.renkin.icon.creator

import android.graphics.PorterDuff
import dev.renkinProject.renkin.data.ImageEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModifierStackTest {
    private val black = ColorizerStyle(firstColor = 0xff000000.toInt())
    private val effect = EffectModifier(ImageEdit.NONE, black)
    private val shadow = ShadowModifier(4f, 3f, 45f, false, black, 1f)

    @Test
    fun presentationPredicates_coverCombinedModifiers() {
        val transforms = listOf(
            TransformModifier() to false,
            TransformModifier(scale = 0.8f) to true,
            TransformModifier(offsetX = 0.2f) to true,
            TransformModifier(offsetY = -0.2f) to true
        )
        val shapes = listOf(null, ShapeModifier(IconShape.CIRCLE, true, 1f, ColorFill(-1)))
        val outlines = listOf(null,
            OutlineModifier(OutlineMode.ADD, 6f, ColorFill(-1)),
            OutlineModifier(OutlineMode.RECOLOR, 6f, ColorFill(-1)))
        val shadows = listOf(
            null to false,
            shadow to true,
            shadow.copy(opacity = 0f) to false,
            shadow.copy(style = black.copy(firstColor = 0)) to false,
            shadow.copy(blur = 0f, distance = 0f) to false
        )
        for ((transform, moved) in transforms) for (shape in shapes) {
            for (outline in outlines) for ((shadow, shadowVisible) in shadows) {
                val stack = ModifierStack(effect, transform, shape, outline, shadow)
                val label = "$transform / $shape / $outline / $shadow"
                val flattened = shape != null || shadowVisible
                val pixels = flattened || outline != null
                assertEquals(label, moved || pixels, stack.hasAdjustments)
                assertEquals(label, pixels, stack.requiresRasterization)
                assertEquals(label, flattened, stack.flattensOutput)
            }
        }
    }

    @Test
    fun shadowVisibility_usesAllGradientStopsAndTheirAlpha() {
        val transparent = black.copy(firstColor = 0x00ffffff, gradientStops = listOf(0))
        assertFalse(shadow.copy(style = transparent).isVisible)
        assertFalse(shadow.copy(style = transparent.copy(mode = ColorizerMode.GRADIENT)).isVisible)
        assertTrue(shadow.copy(style = transparent.copy(
            mode = ColorizerMode.GRADIENT, gradientStops = listOf(0x01000000)
        )).isVisible)
        assertTrue(shadow.copy(style = black.copy(firstColor = 0x01000000)).isVisible)
        assertTrue(shadow.copy(blur = 0f).isVisible)
        assertTrue(shadow.copy(distance = 0f).isVisible)
    }

    @Test
    fun shadowVisibility_nonPositiveAndNonFiniteControlsDoNotActivateIt() {
        for (opacity in listOf(0f, -1f, Float.NaN)) {
            assertFalse(shadow.copy(opacity = opacity).isVisible)
        }
        assertFalse(shadow.copy(blur = Float.NaN, distance = 0f).isVisible)
        assertFalse(shadow.copy(blur = 0f, distance = Float.NaN).isVisible)
        assertFalse(shadow.copy(blur = -1f, distance = -1f).isVisible)
    }

    @Test
    fun blendMode_preservesPrecedenceForCombinedFlags() {
        val cases = listOf(
            black to PorterDuff.Mode.MULTIPLY,
            black.copy(lighten = true) to PorterDuff.Mode.SCREEN,
            black.copy(flat = true) to PorterDuff.Mode.SRC_IN,
            black.copy(flat = true, lighten = true) to PorterDuff.Mode.SRC_IN,
            black.copy(monochrome = true) to PorterDuff.Mode.MULTIPLY,
            black.copy(monochrome = true, flat = true) to PorterDuff.Mode.MULTIPLY,
            black.copy(monochrome = true, lighten = true) to PorterDuff.Mode.MULTIPLY,
            black.copy(monochrome = true, flat = true, lighten = true) to PorterDuff.Mode.MULTIPLY
        )
        cases.forEach { (style, mode) -> assertEquals(mode, style.blendMode) }
    }

    @Test
    fun inactiveGroupsRetainConfigurationWithoutRasterizing() {
        val stack = ModifierStack(
            effect,
            shape = ShapeModifier(IconShape.NONE, false, 0.7f, ColorFill(-1)),
            outline = OutlineModifier(OutlineMode.NONE, 9f, ColorFill(-1)),
            shadow = shadow.copy(enabled = false)
        )
        assertFalse(stack.hasAdjustments)
        assertFalse(stack.requiresRasterization)
        assertFalse(stack.flattensOutput)
        val configuredShadow = requireNotNull(stack.shadow)
        val enabled = stack.copy(shadow = configuredShadow.copy(enabled = true))
        assertTrue(enabled.hasAdjustments)
        assertTrue(enabled.flattensOutput)
        assertEquals(shadow, enabled.shadow)
        assertNotEquals(stack, stack.copy(shadow = configuredShadow.copy(blur = 9f)))
    }
}
