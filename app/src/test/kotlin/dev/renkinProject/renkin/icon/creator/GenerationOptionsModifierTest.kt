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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class GenerationOptionsModifierTest {

    private fun options() = GenerationOptions(
        primarySource = Source.APPLICATION_ICON,
        primaryImageEdit = ImageEdit.COLORIZE,
        primaryTextType = TextType.FULL_NAME,
        primaryIconPack = "",
        color = Color.RED,
        bgColor = Color.TRANSPARENT,
        vector = false,
        materialYou = false,
        themed = false,
        override = true
    )

    @Test
    fun colorizeBlendMode_monochromeWinsOverFlatAndLighten() {
        assertEquals(PorterDuff.Mode.MULTIPLY, options().colorizeBlendMode)
        assertEquals(PorterDuff.Mode.SRC_IN, options().copy(colorizeFlat = true).colorizeBlendMode)
        assertEquals(PorterDuff.Mode.SCREEN, options().copy(colorizeLighten = true).colorizeBlendMode)
        assertEquals(
            PorterDuff.Mode.MULTIPLY,
            options().copy(
                colorizeMonochrome = true,
                colorizeFlat = true,
                colorizeLighten = true
            ).colorizeBlendMode
        )
    }

    @Test
    fun hasVisibleShadow_requiresEnabledOpacityColorAndSpread() {
        val shadow = options().copy(shadowEnabled = true)

        assertTrue(shadow.hasVisibleShadow())
        assertFalse(options().hasVisibleShadow())
        assertFalse(shadow.copy(shadowOpacity = 0f).hasVisibleShadow())
        assertFalse(shadow.copy(shadowBlur = 0f, shadowDistance = 0f).hasVisibleShadow())
        assertFalse(
            shadow.copy(shadowStyle = ColorizerStyle(firstColor = Color.TRANSPARENT))
                .hasVisibleShadow()
        )
        assertTrue(
            shadow.copy(
                shadowStyle = ColorizerStyle(
                    mode = ColorizerMode.GRADIENT,
                    firstColor = Color.TRANSPARENT,
                    gradientStops = listOf(Color.BLUE)
                )
            ).hasVisibleShadow()
        )
    }

    @Test
    fun hasIconAdjustments_coversEveryPostEditStep() {
        assertFalse(options().hasIconAdjustments())
        assertTrue(options().copy(iconOffsetX = 0.1f).hasIconAdjustments())
        assertTrue(options().copy(iconScale = 0.8f).hasIconAdjustments())
        assertTrue(options().copy(iconShape = IconShape.PEBBLE).hasIconAdjustments())
        assertTrue(options().copy(outlineMode = OutlineMode.ADD).hasIconAdjustments())
        assertTrue(options().copy(shadowEnabled = true).hasIconAdjustments())
        assertFalse(options().copy(shadowEnabled = true, shadowOpacity = 0f).hasIconAdjustments())
    }
}
