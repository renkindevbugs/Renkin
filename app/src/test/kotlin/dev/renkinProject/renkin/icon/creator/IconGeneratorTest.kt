package dev.renkinProject.renkin.icon.creator

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Coverage for the icon generator's source skipping and the shared modifier/scale pass on a
 * bitmap icon — the deterministic parts that run on the JVM. The text, icon-pack and
 * edge/path-tracing sources rasterise glyphs / vectors through native libraries
 * (androidx.graphics.path, the canny/tracer JNI), so they are exercised on-device instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class IconGeneratorTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val emptyPack get() = IconPackContainer("", emptyMap())

    private fun options(
        source: Source = Source.APPLICATION_NAME,
        override: Boolean = true,
        iconScale: Float = 1f,
        imageEdit: ImageEdit = ImageEdit.NONE,
        applicationIconVariant: ApplicationIconVariant = ApplicationIconVariant.DEFAULT,
        invertMonochrome: Boolean = false,
        iconShape: IconShape = IconShape.NONE,
        colorizeMonochrome: Boolean = false,
        colorizeInverse: Boolean = false,
        color: Int = Color.BLACK,
        bgColor: Int = Color.WHITE
    ) = GenerationOptions(
        primarySource = source,
        primaryImageEdit = imageEdit,
        primaryTextType = TextType.ONE_LETTER,
        primaryIconPack = "",
        color = color,
        bgColor = bgColor,
        vector = false,
        materialYou = false,
        themed = false,
        override = override,
        iconScale = iconScale,
        applicationIconVariant = applicationIconVariant,
        invertMonochrome = invertMonochrome,
        iconShape = iconShape,
        colorizeMonochrome = colorizeMonochrome,
        colorizeInverse = colorizeInverse
    )

    private fun app(
        createdIcon: IconPackDrawable? = null,
        icon: Drawable = ColorDrawable(Color.RED)
    ) = PackageInfoStruct(
        appName = "Renkin",
        packageName = "dev.test.app",
        activityName = "dev.test.app.Main",
        icon = icon,
        iconID = 0,
        createdIcon = createdIcon
    )

    private fun bitmapIcon(size: Int = 256): BitmapIconDrawable {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        return BitmapIconDrawable(bitmap)
    }

    private fun generator(options: GenerationOptions) =
        IconGenerator(context, options, emptyPack, emptyPack)

    private fun generateOnce(options: GenerationOptions, app: PackageInfoStruct): IconPackDrawable? {
        var result: IconPackDrawable? = null
        var called = false
        generator(options).generateIcon(app) { _, icon, _ ->
            called = true
            result = icon
        }
        return if (called) result else null
    }

    @Test
    fun noneSourceProducesNoIcon() {
        assertNull(generateOnce(options(source = Source.NONE), app()))
    }

    @Test
    fun existingIconIsKeptWhenOverrideIsOff() {
        // The app already has an icon and override is off, so generation must be skipped
        // (no callback, no replacement) before it ever touches the text/vector pipeline.
        val skipped = generateOnce(options(override = false), app(createdIcon = bitmapIcon()))
        assertNull(skipped)
    }

    @Test
    fun modifierWithNoEditAndNoScaleReturnsTheSameIcon() {
        val base = bitmapIcon()
        val result = generator(options(iconScale = 1f)).applyModifier(base, ImageEdit.NONE)
        assertSame(base, result)
    }

    @Test
    fun monochromeVariantDesaturatesOriginalIconWhileDefaultKeepsItsColor() {
        val originalColor = Color.argb(180, 210, 70, 25)
        val sourceBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
            eraseColor(originalColor)
        }
        val sourceApp = app(icon = BitmapDrawable(context.resources, sourceBitmap))

        val defaultPixel = generateOnce(
            options(source = Source.APPLICATION_ICON), sourceApp
        )!!.toBitmap().getPixel(2, 2)
        val monochromePixel = generateOnce(
            options(
                source = Source.APPLICATION_ICON,
                applicationIconVariant = ApplicationIconVariant.MONOCHROME
            ),
            sourceApp
        )!!.toBitmap().getPixel(2, 2)

        assertEquals(originalColor, defaultPixel)
        assertEquals(Color.alpha(originalColor), Color.alpha(monochromePixel))
        assertEquals(Color.red(monochromePixel), Color.green(monochromePixel))
        assertEquals(Color.green(monochromePixel), Color.blue(monochromePixel))
        assertTrue(monochromePixel != originalColor)
    }

    @Test
    fun materialYouMaskUsesForegroundAndBackgroundColors() {
        val mask = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.TRANSPARENT)
            setPixel(1, 0, Color.WHITE)
        }
        val result = recolorMaterialYouMask(mask, Color.BLACK, Color.WHITE)

        assertEquals(Color.WHITE, result.getPixel(0, 0))
        assertEquals(Color.BLACK, result.getPixel(1, 0))
    }

    @Test
    fun materialYouMaskPreservesTransparentBackground() {
        val mask = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.TRANSPARENT)
            setPixel(1, 0, Color.WHITE)
        }

        val result = recolorMaterialYouMask(mask, Color.RED, Color.TRANSPARENT)

        assertEquals(Color.TRANSPARENT, result.getPixel(0, 0))
        assertEquals(Color.RED, result.getPixel(1, 0))
    }

    @Test
    fun materialYouVariantGeneratesUnofficialDuotoneWhenLayerIsMissing() {
        val sourceBitmap = Bitmap.createBitmap(90, 90, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) {
                setPixel(x, y, if (x < width / 2) Color.BLACK else Color.WHITE)
            }
        }
        val sourceApp = app(icon = BitmapDrawable(context.resources, sourceBitmap))

        val generated = generateOnce(
            options(
                source = Source.APPLICATION_ICON,
                applicationIconVariant = ApplicationIconVariant.MATERIAL_YOU
            ),
            sourceApp
        )!!
        val bitmap = generated.toBitmap()

        assertTrue(generated.isAdaptiveIcon())
        assertEquals(Color.BLACK, bitmap.getPixel(30, 45))
    }

    @Test
    fun generatedMaterialYouWithTransparentBackgroundUsesFlatExport() {
        val sourceBitmap = Bitmap.createBitmap(90, 90, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }

        val generated = generateOnce(
            options(
                source = Source.APPLICATION_ICON,
                applicationIconVariant = ApplicationIconVariant.MATERIAL_YOU,
                color = Color.RED,
                bgColor = Color.TRANSPARENT
            ),
            app(icon = BitmapDrawable(context.resources, sourceBitmap))
        ) as BitmapIconDrawable

        assertFalse(generated.isAdaptiveIcon())
        assertEquals(1f, generated.previewScale)
    }

    @Test
    fun materialYouBitmapModifierKeepsAdaptivePreviewPresentation() {
        val sourceBitmap = Bitmap.createBitmap(90, 90, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }

        val modified = generateOnce(
            options(
                source = Source.APPLICATION_ICON,
                imageEdit = ImageEdit.COLORIZE,
                applicationIconVariant = ApplicationIconVariant.MATERIAL_YOU
            ),
            app(icon = BitmapDrawable(context.resources, sourceBitmap))
        ) as BitmapIconDrawable

        assertTrue(modified.isAdaptiveIcon())
        assertEquals(1.5f, modified.previewScale)
    }

    @Test
    fun materialYouShapeBakesPreviewZoomBeforeDroppingAdaptiveMetadata() {
        val sourceBitmap = Bitmap.createBitmap(90, 90, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
            for (y in 30 until 60) for (x in 30 until 60) setPixel(x, y, Color.WHITE)
        }
        val sourceApp = app(icon = BitmapDrawable(context.resources, sourceBitmap))
        val plain = generateOnce(
            options(
                source = Source.APPLICATION_ICON,
                applicationIconVariant = ApplicationIconVariant.MATERIAL_YOU
            ),
            sourceApp
        ) as BitmapIconDrawable
        val shaped = generateOnce(
            options(
                source = Source.APPLICATION_ICON,
                applicationIconVariant = ApplicationIconVariant.MATERIAL_YOU,
                iconShape = IconShape.CIRCLE
            ),
            sourceApp
        ) as BitmapIconDrawable

        assertEquals(1.5f, previewScaleToBakeForShape(plain, shaped = true))
        assertEquals(1f, previewScaleToBakeForShape(plain, shaped = false))
        assertFalse(shaped.isAdaptiveIcon())
        assertEquals(1f, shaped.previewScale)
    }

    @Test
    fun reverseMonochromeInvertsLuminanceAndPreservesAlpha() {
        val sourceColor = Color.argb(170, 35, 35, 35)
        val sourceBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(sourceColor)
        }
        val sourceApp = app(icon = BitmapDrawable(context.resources, sourceBitmap))

        val normal = generateOnce(
            options(
                source = Source.APPLICATION_ICON,
                applicationIconVariant = ApplicationIconVariant.MONOCHROME
            ),
            sourceApp
        )!!.toBitmap().getPixel(0, 0)
        val reversed = generateOnce(
            options(
                source = Source.APPLICATION_ICON,
                applicationIconVariant = ApplicationIconVariant.MONOCHROME,
                invertMonochrome = true
            ),
            sourceApp
        )!!.toBitmap().getPixel(0, 0)

        assertEquals(Color.alpha(normal), Color.alpha(reversed))
        assertEquals(255, Color.red(normal) + Color.red(reversed))
        assertEquals(Color.red(reversed), Color.green(reversed))
        assertEquals(Color.green(reversed), Color.blue(reversed))
    }

    @Test
    fun colorizeMonochromeMatchesApplicationIconMonochromeTransform() {
        val sourceColor = Color.argb(190, 200, 75, 30)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(sourceColor)
        }
        val base = BitmapIconDrawable(bitmap)

        val normal = generator(
            options(colorizeMonochrome = true)
        ).applyModifier(base, ImageEdit.COLORIZE).toBitmap()
        val inverse = generator(
            options(colorizeMonochrome = true, colorizeInverse = true)
        ).applyModifier(base, ImageEdit.COLORIZE).toBitmap()

        assertEquals(monochromeBitmap(bitmap, invert = false).getPixel(0, 0), normal.getPixel(0, 0))
        assertEquals(monochromeBitmap(bitmap, invert = true).getPixel(0, 0), inverse.getPixel(0, 0))
    }

    @Test
    fun invertBitmapColorsPreservesAlphaAndDensity() {
        val sourceColor = Color.argb(123, 10, 20, 30)
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            density = 420
            eraseColor(sourceColor)
        }

        val inverted = invertBitmapColors(bitmap)

        assertEquals(Color.argb(123, 245, 235, 225), inverted.getPixel(0, 0))
        assertEquals(420, inverted.density)
    }

    @Test
    fun malformedPackIconIsSkippedNotThrown() {
        // A pack drawable that blows up while rasterising stands in for a malformed
        // third-party pack icon. Generation must swallow it (skip the app) rather than
        // letting the exception abort the whole refresh.
        val throwing = object : Drawable() {
            override fun draw(canvas: Canvas) = throw RuntimeException("malformed icon")
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = PixelFormat.OPAQUE
            override fun getIntrinsicWidth() = 100
            override fun getIntrinsicHeight() = 100
        }
        val badApp = app()
        val pack = IconPackContainer(
            "",
            mapOf(
                InstalledApplication(badApp.packageName, badApp.activityName, 0)
                    to ResourceDrawable(0, throwing)
            )
        )
        val gen = IconGenerator(context, options(source = Source.ICON_PACK), pack, emptyPack)

        var called = false
        gen.generateIcons(listOf(badApp)) { _, _, _, _ -> called = true }

        assertFalse("the malformed app should be skipped, not delivered", called)
    }

    @Test
    fun modifierScaleRasterisesButKeepsFrameSize() {
        val base = bitmapIcon(256)
        // A non-1 scale rasterises around the centre while keeping the original frame size.
        val scaled = generator(options(iconScale = 0.5f)).applyModifier(base, ImageEdit.NONE)
        assertNotNull(scaled)
        val bitmap = scaled.toBitmap()
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
    }
}
