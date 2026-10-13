package dev.renkinProject.renkin.packages

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstalledIconMemoryDiagnosticsTest {
    private fun bitmap() = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)

    @Test fun sharedBitmapAndRepeatedWrappersAreCountedByIdentity() {
        val pixels = bitmap()
        val first = BitmapDrawable(null, pixels)
        val second = BitmapDrawable(null, pixels)
        val result = inspectInstalledIconMemory(listOf(first, second, first))
        assertEquals(3, result.roots)
        assertEquals(2, result.drawables)
        assertEquals(2, result.bitmapDrawables)
        assertEquals(1, result.bitmaps)
        assertEquals(pixels.allocationByteCount.toLong(), result.bitmapBytes)
        assertFalse(pixels.isRecycled)
    }

    @Test fun adaptiveLayersWrappersAndMonochromeRemainUnchanged() {
        val foreground = BitmapDrawable(null, bitmap())
        val monochrome = BitmapDrawable(null, bitmap())
        val inset = InsetDrawable(foreground, 0.25f)
        val adaptive = AdaptiveIconDrawable(ColorDrawable(), inset, monochrome)
        val layers = LayerDrawable(arrayOf(adaptive, foreground))
        layers.bounds = Rect(4, 5, 70, 80)
        val previousBounds = Rect(layers.bounds)
        val result = inspectInstalledIconMemory(listOf(layers))
        assertEquals(1, result.adaptiveDrawables)
        assertEquals(2, result.bitmaps)
        assertEquals((foreground.bitmap.allocationByteCount + monochrome.bitmap.allocationByteCount).toLong(), result.bitmapBytes)
        assertSame(inset, adaptive.foreground)
        assertSame(foreground, inset.drawable)
        assertEquals(previousBounds, layers.bounds)
        assertEquals(0, result.unknownLeaves)
        assertEquals(0, result.readFailures)
    }

    @Test fun unknownDrawablesAreNeverRenderedAndContainersRemainOpaque() {
        val unknown = object : Drawable() {
            override fun draw(canvas: Canvas) = error("Diagnostic must not render")
            override fun setAlpha(alpha: Int) = error("Diagnostic must not mutate alpha")
            override fun setColorFilter(colorFilter: ColorFilter?) = error("Diagnostic must not mutate filter")
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        val states = StateListDrawable().apply { addState(intArrayOf(), BitmapDrawable(null, bitmap())) }
        val result = inspectInstalledIconMemory(listOf(unknown, states))
        assertEquals(1, result.unknownLeaves)
        assertEquals(1, result.opaqueContainers)
        assertEquals(0, result.bitmaps)
    }

    @Test fun recycledPixelsAreReportedWithoutReadingTheirAllocation() {
        val pixels = bitmap()
        val icon = BitmapDrawable(null, pixels)
        pixels.recycle()
        val result = inspectInstalledIconMemory(listOf(icon))
        assertEquals(1, result.recycledBitmaps)
        assertEquals(0L, result.bitmapBytes)
        assertEquals(0, result.readFailures)
    }

    @Test fun wrapperCyclesAndVeryDeepGraphsCannotHangOrOverflow() {
        val cycle = object : DrawableWrapper(null) {
            override fun getDrawable(): Drawable = this
        }
        var deep: Drawable = BitmapDrawable(null, bitmap())
        repeat(40) { deep = InsetDrawable(deep, 1) }
        val result = inspectInstalledIconMemory(listOf(cycle, deep))
        assertEquals(1, result.truncated)
        assertEquals(0, result.bitmaps)
        assertEquals(0, result.readFailures)
    }

    @Test
    @Config(sdk = [23])
    fun minimumSdkCanInspectRegularBitmapsAndWrappers() {
        val pixels = bitmap()
        val result = inspectInstalledIconMemory(listOf(InsetDrawable(BitmapDrawable(null, pixels), 1)))
        assertEquals(1, result.bitmaps)
        assertEquals(pixels.allocationByteCount.toLong(), result.bitmapBytes)
        assertEquals(0, result.adaptiveDrawables)
        assertEquals(0, result.hardwareBitmaps)
    }
}
