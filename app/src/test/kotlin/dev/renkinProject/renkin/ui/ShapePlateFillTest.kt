package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import dev.renkinProject.renkin.icon.creator.ApplicationIconVariant
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.icon.creator.ColorFill
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.EffectModifier
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconAdjustmentPipeline
import dev.renkinProject.renkin.icon.creator.IconExportOptions
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.IconSourceOptions
import dev.renkinProject.renkin.icon.creator.ModifierPresetPayload
import dev.renkinProject.renkin.icon.creator.ModifierPresetShape
import dev.renkinProject.renkin.icon.creator.ModifierStack
import dev.renkinProject.renkin.icon.creator.plateFill
import dev.renkinProject.renkin.icon.creator.withModifierPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A non-cropping shape plate is painted in its own colour. On a Material You source the
 * presentation background keeps the wallpaper colour, so a plate that inherited it painted the
 * wallpaper colour instead of the chosen one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShapePlateFillTest {
    private val wallpaper = Color.rgb(0, 40, 110)
    private val white = ColorizerStyle(firstColor = Color.WHITE)
    private val redToBlue = ColorizerStyle(
        mode = ColorizerMode.GRADIENT,
        firstColor = Color.RED,
        gradientStops = listOf(Color.BLUE),
        gradientPositions = listOf(0f, 1f),
        gradientAngle = 90f
    )

    private fun materialYouSource() = GenerationOptions(
        source = IconSourceOptions(
            primarySource = Source.APPLICATION_ICON,
            applicationIconVariant = ApplicationIconVariant.MATERIAL_YOU
        ),
        export = IconExportOptions(background = ColorFill(wallpaper)),
        modifiers = ModifierStack(EffectModifier(ImageEdit.NONE, ColorizerStyle(firstColor = Color.WHITE))),
        override = true
    )

    private fun plate(style: ColorizerStyle) = AdjustmentState().apply {
        iconShape = IconShape.CIRCLE
        shapeCrop = false
        shapeStyle = style
    }

    private fun render(options: GenerationOptions): Bitmap {
        val transparent = BitmapIconDrawable(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888))
        return IconAdjustmentPipeline(RuntimeEnvironment.getApplication().resources, options)
            .apply(transparent).toBitmap()
    }

    @Test fun solidPlateOnMaterialYouSourceIsPaintedInThePlateColour() {
        val options = materialYouSource().withModifierAdjustments(plate(white), ImageEdit.NONE, null)

        assertEquals(ColorFill(Color.WHITE, white), options.modifiers.shape!!.fill)
        assertEquals(wallpaper, options.export.background.color)
        assertEquals(Color.WHITE, render(options).getPixel(128, 128))
    }

    @Test fun gradientPlateOnMaterialYouSourceKeepsItsGradient() {
        val result = render(materialYouSource().withModifierAdjustments(plate(redToBlue), ImageEdit.NONE, null))

        assertTrue(Color.red(result.getPixel(40, 128)) > 180)
        assertTrue(Color.blue(result.getPixel(216, 128)) > 180)
    }

    @Test fun presetPlateOnMaterialYouSourceIsPaintedInThePlateColour() {
        val options = materialYouSource().withModifierPreset(
            ModifierPresetPayload(shape = ModifierPresetShape(IconShape.CIRCLE, false, 1f, white))
        )

        assertEquals(wallpaper, options.export.background.color)
        assertEquals(Color.WHITE, render(options).getPixel(128, 128))
    }

    @Test fun croppingOrMissingShapeHasNoPlate() {
        assertNull(plateFill(IconShape.CIRCLE, crop = true, style = white))
        assertNull(plateFill(IconShape.NONE, crop = false, style = white))
        assertEquals(ColorFill(Color.RED, redToBlue), plateFill(IconShape.CIRCLE, crop = false, style = redToBlue))
    }
}
