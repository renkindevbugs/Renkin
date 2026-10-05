package dev.renkinProject.renkin.icon.creator

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment

/**
 * The surfaces a gradient fills rather than tints: the shape plate, the themed background and the
 * Material You layers. NATIVE graphics — these assert real pixels, which the legacy Robolectric
 * canvas would leave blank.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GradientFillTest {

    private val leftToRight = ColorizerStyle(
        mode = ColorizerMode.GRADIENT,
        firstColor = Color.RED,
        gradientStops = listOf(Color.BLUE),
        gradientPositions = listOf(0f, 1f),
        // 90° sweeps left to right, the way the gallery and every web editor show a gradient.
        gradientAngle = 90f
    )

    @Test
    fun gradientPixelsPaintsAcrossTheRequestedSize() {
        val pixels = requireNotNull(gradientPixels(leftToRight, 8, 1))

        assertEquals(8, pixels.size)
        // Pixel centres sit half a step inside the span, so the ends are all-but-pure rather than
        // exactly the stop colours; what matters is which end each stop landed on.
        assertTrue(Color.red(pixels.first()) > 200 && Color.blue(pixels.first()) < 60)
        assertTrue(Color.blue(pixels.last()) > 200 && Color.red(pixels.last()) < 60)
        assertTrue(Color.red(pixels[3]) in 60..200)
    }

    @Test
    fun gradientPixelsAreNullForAFlatColour() {
        assertNull(gradientPixels(ColorizerStyle(firstColor = Color.RED), 4, 4))
        assertNull(gradientPixels(null, 4, 4))
    }

    @Test
    fun backgroundShaderOnlyExistsForAGradientStyle() {
        assertNull(options().backgroundShader(8, 8))
        assertNull(
            options(ColorizerStyle(firstColor = Color.RED)).backgroundShader(8, 8)
        )
        assertNotNull(options(leftToRight).backgroundShader(8, 8))
    }

    @Test
    fun materialYouMaskFillsBothLayersFromTheirGradients() {
        // Fully covered on the left half, empty on the right: foreground shows through one side,
        // background the other, each sampling its own gradient at that position.
        val mask = Bitmap.createBitmap(4, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.WHITE)
            setPixel(1, 0, Color.WHITE)
            setPixel(2, 0, Color.TRANSPARENT)
            setPixel(3, 0, Color.TRANSPARENT)
        }
        val background = ColorizerStyle(
            mode = ColorizerMode.GRADIENT,
            firstColor = Color.GREEN,
            gradientStops = listOf(Color.BLACK),
            gradientPositions = listOf(0f, 1f),
            gradientAngle = 90f
        )

        val result = recolorMaterialYouMask(
            mask,
            leftToRight.firstColor,
            background.firstColor,
            leftToRight,
            background
        )

        // Leftmost covered pixel comes from the foreground gradient's red end; the rightmost
        // empty one from the background gradient's black end.
        val covered = result.getPixel(0, 0)
        val empty = result.getPixel(3, 0)
        assertTrue(Color.red(covered) > 200 && Color.blue(covered) < 60)
        assertTrue(Color.green(empty) < 60 && Color.alpha(empty) == 255)
    }

    @Test
    fun materialYouMaskKeepsFlatColoursWhenThereIsNoStyle() {
        val mask = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.TRANSPARENT)
            setPixel(1, 0, Color.WHITE)
        }

        val result = recolorMaterialYouMask(mask, Color.BLACK, Color.WHITE)

        assertEquals(Color.WHITE, result.getPixel(0, 0))
        assertEquals(Color.BLACK, result.getPixel(1, 0))
    }

    @Test
    fun inheritedPlateFollowsBackgroundOnlyCopies() {
        val base = plateOptions().withExport { copy(background = background.copy(color = Color.RED)) }
        assertEquals(Color.RED, renderPlate(base).getPixel(128, 128))
        val updated = base.withExport { copy(background = background.copy(color = Color.GREEN)) }
        assertEquals(Color.GREEN, renderPlate(updated).getPixel(128, 128))
        assertNull(updated.modifiers.shape?.fill)
    }

    @Test
    fun inheritedPlateFollowsAChangedGradientBackground() {
        val result = renderPlate(plateOptions().withExport { copy(background = background.copy(style = leftToRight)) })
        assertTrue(Color.red(result.getPixel(40, 128)) > 180)
        assertTrue(Color.blue(result.getPixel(216, 128)) > 180)
    }

    @Test
    fun explicitPlateFillIsIndependentOfPresentationBackground() {
        val base = plateOptions()
        val updated = base.copy(
            export = base.export.copy(background = ColorFill(Color.RED, leftToRight)),
            modifiers = base.modifiers.copy(shape = base.modifiers.shape!!.copy(fill = ColorFill(Color.GREEN)))
        )
        assertEquals(Color.GREEN, renderPlate(updated).getPixel(128, 128))
    }

    private fun plateOptions(): GenerationOptions {
        val base = options()
        return base.copy(modifiers = base.modifiers.copy(shape = ShapeModifier(IconShape.CIRCLE, false, 1f)))
    }

    private fun renderPlate(options: GenerationOptions): Bitmap {
        val source = BitmapIconDrawable(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888))
        return IconAdjustmentPipeline(RuntimeEnvironment.getApplication().resources, options).apply(source).toBitmap()
    }

    private fun options(backgroundStyle: ColorizerStyle? = null) = GenerationOptions(
        source = IconSourceOptions(
            primarySource = Source.NONE,
            primaryTextType = TextType.FULL_NAME,
            primaryIconPack = ""
        ),
        export = IconExportOptions(
            vector = false,
            materialYou = false,
            themed = false,
            background = ColorFill(
                color = Color.WHITE,
                style = backgroundStyle
            )
        ),
        override = true,
        modifiers = ModifierStack(
            effect = EffectModifier(imageEdit = ImageEdit.NONE, colorizer = ColorizerStyle(firstColor = Color.WHITE)),
        )
    )
}
