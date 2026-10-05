package dev.renkinProject.renkin.icon.creator

import android.app.Application

import android.graphics.Color
import android.graphics.PorterDuff
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class GenerationOptionsModifierTest {

    private val baseStyle = ColorizerStyle(firstColor = Color.RED)
    private val baseStack = ModifierStack(EffectModifier(ImageEdit.COLORIZE, baseStyle))
    private val baseShadow = ShadowModifier(
        SHADOW_BLUR_DEFAULT, SHADOW_DISTANCE_DEFAULT, SHADOW_ANGLE_DEFAULT,
        false, ColorizerStyle(firstColor = Color.BLACK), SHADOW_OPACITY_DEFAULT
    )

    private fun options(modifiers: ModifierStack = baseStack) = GenerationOptions(
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
        modifiers = modifiers
    )

    private fun optionsWithShadow(shadow: ShadowModifier): GenerationOptions =
        options(baseStack.copy(shadow = shadow))

    @Test
    fun colorizeBlendMode_monochromeWinsOverFlatAndLighten() {
        assertEquals(PorterDuff.Mode.MULTIPLY, options().modifiers.effect.colorizer.blendMode)
        assertEquals(PorterDuff.Mode.SRC_IN, options().withColorizerStyle(baseStyle.copy(flat = true)).modifiers.effect.colorizer.blendMode)
        assertEquals(PorterDuff.Mode.SCREEN, options().withColorizerStyle(baseStyle.copy(lighten = true)).modifiers.effect.colorizer.blendMode)
        assertEquals(
            PorterDuff.Mode.MULTIPLY,
            options().withColorizerStyle(baseStyle.copy(
                monochrome = true, flat = true, lighten = true
            )).modifiers.effect.colorizer.blendMode
        )
    }

    @Test
    fun hasVisibleShadow_requiresEnabledOpacityColorAndSpread() {
        assertTrue(optionsWithShadow(baseShadow).hasVisibleShadow())
        assertFalse(options().hasVisibleShadow())
        assertFalse(optionsWithShadow(baseShadow.copy(opacity = 0f)).hasVisibleShadow())
        assertFalse(optionsWithShadow(baseShadow.copy(blur = 0f, distance = 0f)).hasVisibleShadow())
        assertFalse(optionsWithShadow(baseShadow.copy(
            style = ColorizerStyle(firstColor = Color.TRANSPARENT)
        )).hasVisibleShadow())
        assertTrue(optionsWithShadow(baseShadow.copy(
            style = ColorizerStyle(
                mode = ColorizerMode.GRADIENT,
                firstColor = Color.TRANSPARENT,
                gradientStops = listOf(Color.BLUE)
            )
        )).hasVisibleShadow())
    }

    @Test
    fun hasIconAdjustments_coversEveryPostEditStep() {
        assertFalse(options().hasIconAdjustments())
        assertTrue(options(baseStack.copy(transform = TransformModifier(offsetX = 0.1f))).hasIconAdjustments())
        assertTrue(options(baseStack.copy(transform = TransformModifier(scale = 0.8f))).hasIconAdjustments())
        assertTrue(options(baseStack.copy(shape = ShapeModifier(IconShape.PEBBLE, true, 1f))).hasIconAdjustments())
        assertTrue(options(baseStack.copy(outline = OutlineModifier(OutlineMode.ADD, 6f, ColorFill(Color.BLACK)))).hasIconAdjustments())
        assertTrue(optionsWithShadow(baseShadow).hasIconAdjustments())
        assertFalse(optionsWithShadow(baseShadow.copy(opacity = 0f)).hasIconAdjustments())
    }

    @Test
    fun sourceOnlyCopyRetainsTheOwnedModifierStack() {
        val original = optionsWithShadow(baseShadow.copy(enabled = false))
        val updated = original.withSource { copy(primarySource = Source.APPLICATION_NAME, textCustom = "R") }
        assertSame(original.modifiers, updated.modifiers)
        assertEquals(original.modifiers.effect.colorizer, updated.modifiers.effect.colorizer)
        assertEquals(original.modifiers.shadow, updated.modifiers.shadow)
    }
}
