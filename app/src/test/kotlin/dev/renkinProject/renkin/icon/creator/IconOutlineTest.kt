package dev.renkinProject.renkin.icon.creator

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel checks of the outline step (native graphics — real draws). A centred opaque disc
 * stands in for an icon: ADD must paint the band just outside it, RECOLOR must repaint the
 * disc's rim while leaving its centre and the transparent surroundings alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconOutlineTest {

    private fun disc(size: Int = 64, radius: Float = 20f, color: Int = Color.BLUE): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        Canvas(bitmap).drawCircle(size / 2f, size / 2f, radius, paint)
        return bitmap
    }

    @Test
    fun add_paintsTheBandOutsideTheSilhouette() {
        val out = IconOutline.apply(disc(), OutlineMode.ADD, widthPx = 6f, color = Color.RED)

        // Just outside the disc (radius 20 + ~3): outline colour.
        val outside = out.getPixel(32 + 23, 32)
        assertEquals(0xFF, Color.alpha(outside))
        assertTrue(Color.red(outside) > 200 && Color.blue(outside) < 60)
        // Disc centre keeps the original colour.
        assertEquals(Color.BLUE, out.getPixel(32, 32))
        // Far corner stays transparent.
        assertEquals(0, out.getPixel(2, 2))
    }

    @Test
    fun recolor_repaintsTheRimOnly() {
        val out = IconOutline.apply(disc(), OutlineMode.RECOLOR, widthPx = 4f, color = Color.RED)

        // Rim (just inside radius 20): recoloured, still opaque.
        val rim = out.getPixel(32 + 18, 32)
        assertTrue(Color.red(rim) > 200 && Color.blue(rim) < 60)
        // Centre keeps the original colour; nothing grew outside.
        assertEquals(Color.BLUE, out.getPixel(32, 32))
        assertEquals(0, out.getPixel(32 + 25, 32))
    }

    @Test
    fun none_returnsTheSourceUntouched() {
        val src = disc()
        assertTrue(IconOutline.apply(src, OutlineMode.NONE, 6f, Color.RED) === src)
    }
}
