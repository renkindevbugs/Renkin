package dev.alembiconsProject.alembicons.extension

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BitmapExtensionTest {

    private fun bitmapOf(w: Int, h: Int, pixels: IntArray): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }

    private fun Bitmap.pixels(): IntArray =
        IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    @Test
    fun removeBackground_clearsFlatBorderAndKeepsCentre() {
        val blue = 0xFF0000FF.toInt()
        val red = 0xFFFF0000.toInt()
        val w = 4; val h = 4
        val px = IntArray(w * h) { blue }
        // 2x2 red block in the middle, surrounded by blue.
        px[1 * w + 1] = red; px[1 * w + 2] = red; px[2 * w + 1] = red; px[2 * w + 2] = red

        val out = bitmapOf(w, h, px).removeBackground(0.1f).pixels()

        // The blue background (connected to the border) is erased.
        assertEquals(0, out[0])
        assertEquals(0, out[w - 1])
        assertEquals(0, out[(h - 1) * w])
        // The centred glyph survives.
        assertEquals(red, out[1 * w + 1])
        assertEquals(red, out[2 * w + 2])
    }

    @Test
    fun removeBackground_alreadyTransparentBorder_keepsContent() {
        val red = 0xFFFF0000.toInt()
        val w = 3; val h = 3
        val px = IntArray(w * h) { 0 }
        px[1 * w + 1] = red

        val out = bitmapOf(w, h, px).removeBackground(0.2f).pixels()

        assertEquals(red, out[1 * w + 1])
        assertEquals(0, out[0])
    }

    @Test
    fun removeBackground_glyphTouchingDifferentColourSurvives() {
        // A red glyph that reaches the edge must not be erased — only the blue background is.
        val blue = 0xFF0000FF.toInt()
        val red = 0xFFFF0000.toInt()
        val w = 3; val h = 3
        val px = intArrayOf(
            blue, red, blue,
            blue, red, blue,
            blue, red, blue,
        )

        val out = bitmapOf(w, h, px).removeBackground(0.1f).pixels()

        // Blue columns erased, red stripe (top/bottom touch the edge) kept.
        assertEquals(0, out[0])
        assertEquals(red, out[1])
        assertEquals(red, out[1 * w + 1])
        assertEquals(red, out[2 * w + 1])
    }
}
