package dev.alembiconsProject.alembicons.icon.creator

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import dev.alembiconsProject.alembicons.data.ImageEdit
import dev.alembiconsProject.alembicons.data.InstalledApplication
import dev.alembiconsProject.alembicons.data.Source
import dev.alembiconsProject.alembicons.data.TextType
import dev.alembiconsProject.alembicons.drawable.BitmapIconDrawable
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        iconScale: Float = 1f
    ) = GenerationOptions(
        primarySource = source,
        primaryImageEdit = ImageEdit.NONE,
        primaryTextType = TextType.ONE_LETTER,
        primaryIconPack = "",
        color = Color.BLACK,
        bgColor = Color.WHITE,
        vector = false,
        monochrome = false,
        themed = false,
        override = override,
        iconScale = iconScale
    )

    private fun app(createdIcon: IconPackDrawable? = null) = PackageInfoStruct(
        appName = "Renkin",
        packageName = "dev.test.app",
        activityName = "dev.test.app.Main",
        icon = ColorDrawable(Color.RED),
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
