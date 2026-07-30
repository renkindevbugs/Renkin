package dev.renkinProject.renkin.extension

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

    private val blue = 0xFF0000FF.toInt()
    private val red = 0xFFFF0000.toInt()

    private fun bitmapOf(w: Int, h: Int, pixels: IntArray): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }

    private fun Bitmap.pixels(): IntArray =
        IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    @Test
    fun removeBackground_clearsFlatBorderAndKeepsCentre() {
        val w = 4; val h = 4
        val px = IntArray(w * h) { blue }
        px[1 * w + 1] = red; px[1 * w + 2] = red; px[2 * w + 1] = red; px[2 * w + 2] = red

        val out = bitmapOf(w, h, px).removeBackground(0.1f).pixels()

        assertEquals(0, out[0])
        assertEquals(0, out[w - 1])
        assertEquals(red, out[1 * w + 1])
        assertEquals(red, out[2 * w + 2])
    }

    @Test
    fun removeBackground_crossesTransparentPaddingAndStripsFrame() {
        // Transparent padding -> blue frame -> red glyph: the frame (reached across the padding) is
        // erased, the inner glyph survives. This is the adaptive-icon shape the flat version missed.
        val t = 0
        val w = 5; val h = 5
        val px = intArrayOf(
            t, t, t, t, t,
            t, blue, blue, blue, t,
            t, blue, red, blue, t,
            t, blue, blue, blue, t,
            t, t, t, t, t,
        )

        val out = bitmapOf(w, h, px).removeBackground(0.1f).pixels()

        assertEquals(0, out[0])            // padding
        assertEquals(0, out[1 * w + 1])    // frame erased
        assertEquals(red, out[2 * w + 2])  // glyph kept
    }

    @Test
    fun removeBackground_lineArtWithoutABackgroundIsKept() {
        // Lawnicons-style artwork: strokes on transparency. There is no background to strip, so
        // the flood would otherwise start on the glyph itself and return an empty tile.
        val t = 0
        val w = 5; val h = 5
        val px = intArrayOf(
            t, t, blue, t, t,
            t, t, blue, t, t,
            blue, blue, blue, blue, blue,
            t, t, blue, t, t,
            t, t, blue, t, t,
        )

        val out = bitmapOf(w, h, px).removeBackground(0.1f).pixels()

        assertEquals(blue, out[2 * w + 2])
        assertEquals(blue, out[0 * w + 2])
    }

    @Test
    fun removeBackground_glyphTouchingEdgeSurvives() {
        // A red stripe that reaches the top/bottom edge must not be erased — only the blue is.
        val w = 3; val h = 3
        val px = intArrayOf(
            blue, red, blue,
            blue, red, blue,
            blue, red, blue,
        )

        val out = bitmapOf(w, h, px).removeBackground(0.1f).pixels()

        assertEquals(0, out[0])
        assertEquals(red, out[1])
        assertEquals(red, out[1 * w + 1])
        assertEquals(red, out[2 * w + 1])
    }

    @Test
    fun contentBounds_findsOffCentreArtwork() {
        val w = 4; val h = 4
        val px = IntArray(w * h)
        px[0] = red // single opaque pixel in the top-left corner

        val bounds = bitmapOf(w, h, px).contentBounds()!!

        assertEquals(0, bounds.left)
        assertEquals(1, bounds.right)
        assertEquals(0, bounds.top)
        assertEquals(1, bounds.bottom)
    }

    @Test
    fun contentBounds_ignoresStraySpecks() {
        // A solid 40×40 block in the middle of 128×128, plus a lone speck at the left edge. The
        // bounds must hug the block — the speck is noise the eye can't see, and would otherwise
        // make auto-centring skew towards it.
        val w = 128; val h = 128
        val px = IntArray(w * h)
        for (y in 44 until 84) for (x in 44 until 84) px[y * w + x] = red
        px[60 * w] = blue // stray speck at x=0

        val bounds = bitmapOf(w, h, px).contentBounds()!!

        assertEquals(44, bounds.left)
        assertEquals(84, bounds.right)
    }

    @Test
    fun translated_movesContentAndDropsOffEdgePixels() {
        val w = 4; val h = 4
        val px = IntArray(w * h)
        px[0] = red // top-left corner

        val out = bitmapOf(w, h, px).translated(1f, 1f).pixels()

        assertEquals(0, out[0])           // old position cleared
        assertEquals(red, out[1 * w + 1]) // moved by (1,1)
    }

    @Test
    fun removeBackground_followsAGradientButStopsAtTheGlyph() {
        // A horizontal blue gradient background with a red glyph column in the middle. Neighbour-wise
        // tolerance follows the gradient across, the hard red edge stops it.
        val w = 7; val h = 1
        val px = IntArray(w) { x ->
            if (x == 3) red else (0xFF000000.toInt() or (200 + x * 5)) // blue ramp 200..230
        }

        val out = bitmapOf(w, h, px).removeBackground(0.05f).pixels()

        assertEquals(0, out[0])  // gradient start erased
        assertEquals(0, out[2])  // ...followed across
        assertEquals(red, out[3]) // glyph kept
        assertEquals(0, out[6])  // gradient end erased from the other side
    }
}
