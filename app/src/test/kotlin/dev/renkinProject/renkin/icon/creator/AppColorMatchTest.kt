package dev.renkinProject.renkin.icon.creator

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import dev.renkinProject.renkin.ui.addMatchedLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppColorMatchTest {
    private val resources get() = RuntimeEnvironment.getApplication().resources
    private val green = Color.rgb(37, 211, 102)
    private val yellow = Color.rgb(255, 252, 0)

    private fun bitmap(size: Int = 108, draw: Canvas.() -> Unit) =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { Canvas(this).draw() }

    private fun logo(fill: Int, outline: Int? = null) = bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        outline?.let { drawCircle(54f, 54f, 26f, paint.apply { color = it }) }
        drawCircle(54f, 54f, 22f, paint.apply { color = fill })
    }

    private fun adaptive(background: Drawable, foreground: Bitmap) =
        AdaptiveIconDrawable(background, BitmapDrawable(resources, foreground))

    private fun Int.near(other: Int) =
        abs(Color.red(this) - Color.red(other)) + abs(Color.green(this) - Color.green(other)) +
            abs(Color.blue(this) - Color.blue(other)) < 24

    private fun found(icon: Drawable) = (appBackgroundColor(icon) as AppBackgroundColor.Found).style

    @Test
    fun whiteLogoOnBrandColourGetsThatColour() {
        val background = appBackgroundColor(adaptive(ColorDrawable(green), logo(Color.WHITE)))
        val match = matchAppColor(logo(Color.WHITE), background) as AppColorMatch.Available
        assertEquals(1, match.layers.size)
        val painted = applySegmentLayers(logo(Color.WHITE), listOf(match.logo))

        assertTrue(match.logo.style.firstColor.near(green))
        assertTrue(painted.getPixel(54, 54).near(green))
    }

    @Test
    fun outlineKeepsItsColourWhileTheWhiteGhostIsRecoloured() {
        val icon = bitmap {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            drawCircle(54f, 54f, 40f, paint.apply { color = Color.BLACK })
            drawCircle(54f, 54f, 36f, paint.apply { color = Color.WHITE })
        }
        val background = appBackgroundColor(adaptive(ColorDrawable(yellow), icon))
        val layers = (matchAppColor(icon, background) as AppColorMatch.Available).layers
        val painted = applySegmentLayers(icon, layers)

        assertTrue(painted.getPixel(54, 54).near(yellow))
        assertTrue(painted.getPixel(54, 15).near(Color.BLACK))
    }

    @Test
    fun neutralBackgroundsAndNonAdaptiveIconsAreReported() {
        assertEquals(
            AppBackgroundColor.Missing(AppColorUnavailable.NEUTRAL_BACKGROUND),
            appBackgroundColor(adaptive(ColorDrawable(Color.WHITE), logo(Color.RED)))
        )
        assertEquals(
            AppBackgroundColor.Missing(AppColorUnavailable.NOT_ADAPTIVE),
            appBackgroundColor(BitmapDrawable(resources, logo(Color.RED)))
        )
    }

    @Test
    fun colourfulLogoHasNothingToMatch() {
        val background = appBackgroundColor(adaptive(ColorDrawable(green), logo(Color.RED)))
        assertEquals(
            AppColorMatch.Unavailable(AppColorUnavailable.NO_NEUTRAL_LOGO),
            matchAppColor(logo(Color.RED), background)
        )
    }

    @Test
    fun gradientBackgroundBecomesAGradientInItsDirection() {
        val top = Color.rgb(250, 60, 120)
        val bottom = Color.rgb(90, 40, 220)
        val gradient = bitmap {
            drawRect(0f, 0f, 108f, 108f, Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, 108f, top, bottom, Shader.TileMode.CLAMP)
            })
        }
        val style = found(adaptive(BitmapDrawable(resources, gradient), logo(Color.WHITE)))

        assertEquals(ColorizerMode.GRADIENT, style.mode)
        val rendered = requireNotNull(gradientPixels(style, 108, 108))
        val renderedTop = rendered[10 * 108 + 54]
        val renderedBottom = rendered[97 * 108 + 54]
        assertTrue(Color.red(renderedTop) > Color.red(renderedBottom))
        assertTrue(Color.blue(renderedTop) < Color.blue(renderedBottom))
        assertTrue(abs(Color.red(rendered[10 * 108 + 10]) - Color.red(rendered[10 * 108 + 97])) < 12)
    }

    @Test
    fun patternedBackgroundFallsBackToItsCentreColour() {
        val stripes = bitmap {
            drawColor(green)
            drawRect(0f, 0f, 108f, 30f, Paint().apply { color = Color.RED })
        }
        val style = found(adaptive(BitmapDrawable(resources, stripes), logo(Color.WHITE)))
        assertEquals(ColorizerMode.SINGLE_COLOR, style.mode)
        assertTrue(style.firstColor.near(green))
    }

    /** A complete square icon (white logo on a dark plate) as the art of an adaptive layer. */
    private fun legacySquare() = bitmap(96) {
        drawColor(Color.rgb(0, 60, 110))
        drawCircle(48f, 48f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    private fun Bitmap.lightCoverage(): Float {
        var light = 0
        for (y in 0 until height) for (x in 0 until width) {
            val pixel = getPixel(x, y)
            if (Color.alpha(pixel) > 128 && Color.red(pixel) > 200 && Color.blue(pixel) > 200) light++
        }
        return light.toFloat() / (width * height)
    }

    @Test
    fun legacyArtworkInsetInsideTheAdaptiveLayerKeepsItsSize() {
        val inset = 0.167f
        val layer = android.graphics.drawable.InsetDrawable(BitmapDrawable(resources, legacySquare()), inset)
        val icon = AdaptiveIconDrawable(layer, layer.constantState!!.newDrawable().mutate())
        val app = dev.renkinProject.renkin.packages.PackageInfoStruct("App", "dev.test", "dev.test.Main", icon, 0)
        val options = GenerationOptions(
            IconSourceOptions(primarySource = dev.renkinProject.renkin.data.Source.APPLICATION_ICON),
            ModifierStack(EffectModifier(dev.renkinProject.renkin.data.ImageEdit.NONE, ColorizerStyle(firstColor = Color.BLACK))),
            IconExportOptions(), override = true
        )
        val generated = IconGenerator(RuntimeEnvironment.getApplication(), options,
            IconPackContainer("", emptyMap()), IconPackContainer("", emptyMap()))
            .generatePreview(app, null).icon!!.toBitmap()

        // The launcher shows the inner 72 of 108 dp; that crop of an inset square shows the whole
        // art at (1 - 2 * inset) * 1.5 of the frame, not the art enlarged to fill it.
        val expected = legacySquare().lightCoverage() * ((1 - 2 * inset) * 1.5f).let { it * it }
        assertTrue("Logo coverage ${generated.lightCoverage()} vs $expected",
            abs(generated.lightCoverage() - expected) < expected * 0.2f)
    }

    @Test
    fun generatedMaterialYouUsesTheLogoOnADarkPlateAsForeground() {
        val art = legacySquare()
        val pixels = IntArray(art.width * art.height).also { art.getPixels(it, 0, art.width, 0, 0, art.width, art.height) }
        val coverage = materialYouForegroundCoverage(pixels, art.width, art.height)
        assertTrue(coverage[48 * art.width + 48] > 0.9f)
        assertTrue(coverage[4 * art.width + 4] < 0.1f)
    }

    @Test
    fun generatedMaterialYouKeepsDarkArtOnTransparencyAsForeground() {
        val art = bitmap(96) {
            drawCircle(48f, 48f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        }
        val pixels = IntArray(art.width * art.height).also { art.getPixels(it, 0, art.width, 0, 0, art.width, art.height) }
        val coverage = materialYouForegroundCoverage(pixels, art.width, art.height)
        assertTrue(coverage[48 * art.width + 48] > 0.9f)
        assertEquals(0f, coverage[4 * art.width + 4])
    }

    /** Both layers carry the whole legacy icon: a gradient plate with a white logo in the middle. */
    private fun plateWithLogo() = bitmap(96) {
        drawRect(0f, 0f, 96f, 96f, Paint().apply {
            shader = LinearGradient(0f, 0f, 96f, 0f, Color.rgb(0, 190, 230), Color.rgb(0, 40, 80), Shader.TileMode.CLAMP)
        })
        drawCircle(48f, 48f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    @Test
    fun wholeLegacyIconErasesItsPlateAndRecoloursTheLogo() {
        val art = plateWithLogo()
        val icon = adaptive(BitmapDrawable(resources, art), art)
        val background = appBackgroundColor(icon)
        val style = (background as AppBackgroundColor.Found).style
        assertEquals("The logo in the middle must not be read as the background", ColorizerMode.GRADIENT, style.mode)

        val match = matchAppColor(art, background) as AppColorMatch.Available
        assertEquals(2, match.layers.size)
        val painted = applySegmentLayers(art, match.layers)

        assertEquals("Plate kept", 0, Color.alpha(painted.getPixel(6, 48)))
        assertEquals("Plate kept", 0, Color.alpha(painted.getPixel(90, 48)))
        val logo = painted.getPixel(48, 48)
        assertTrue("Logo not recoloured: ${Integer.toHexString(logo)}", Color.alpha(logo) > 200 && !isNeutral(logo))
    }

    @Test
    fun matchedLayerReplacesOnlyTheUntouchedStarterLayer() {
        val matched = SegmentLayer(listOf(Color.WHITE), style = ColorizerStyle(firstColor = green))
        val starter = SegmentLayer(emptyList(), style = ColorizerStyle(firstColor = Color.WHITE))
        val picked = starter.copy(targets = listOf(Color.BLACK))

        assertEquals(listOf(matched), addMatchedLayer(listOf(starter), listOf(matched)).layers)
        assertEquals(listOf(picked, matched), addMatchedLayer(listOf(picked), listOf(matched)).layers)
        assertEquals(1, addMatchedLayer(listOf(picked), listOf(matched)).selectedIndex)
    }
}
