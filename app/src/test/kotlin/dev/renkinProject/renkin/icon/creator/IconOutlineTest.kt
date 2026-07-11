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

    // The debug selection tint (temporary, device-side) would repaint everything magenta;
    // these tests check the real recolour path.
    @org.junit.Before
    fun disableDebugTint() {
        IconOutline.debugTintSelection = false
    }

    @org.junit.After
    fun restoreDebugTint() {
        IconOutline.debugTintSelection = true
    }

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
    fun recolor_uniformShapeRecoloursWholeAndStaysInside() {
        // A solid one-colour disc has no outline/fill jump: the whole disc counts as the
        // outline and is repainted, but nothing may grow outside the silhouette.
        val out = IconOutline.apply(disc(), OutlineMode.RECOLOR, widthPx = 4f, color = Color.RED)

        val rim = out.getPixel(32 + 18, 32)
        assertTrue(Color.red(rim) > 200 && Color.blue(rim) < 60)
        val centre = out.getPixel(32, 32)
        assertTrue(Color.red(centre) > 200 && Color.blue(centre) < 60)
        assertEquals(0, out.getPixel(32 + 25, 32))
    }

    @Test
    fun recolor_stopsAtTheFillEvenWithAGenerousThickness() {
        // A blue ring around a pale fill — the Komikku case. The flood must stop at the
        // ring/fill colour jump, so cranking the thickness up cannot eat into the fill.
        val bitmap = disc(radius = 24f, color = Color.BLUE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(225, 240, 255)
        Canvas(bitmap).drawCircle(32f, 32f, 16f, paint)

        val out = IconOutline.apply(bitmap, OutlineMode.RECOLOR, widthPx = 16f, color = Color.RED)

        // Ring fully recoloured, from its outer to its inner edge.
        assertTrue(Color.red(out.getPixel(32 + 22, 32)) > 180)
        assertTrue(Color.red(out.getPixel(32 + 18, 32)) > 180)
        // The pale fill keeps its colour — the flood stopped at the jump.
        val fill = out.getPixel(32, 32)
        assertTrue(Color.blue(fill) > 200 && Color.green(fill) > 200)
    }

    @Test
    fun eraseOutline_undoesTheOutlineOnlyInsideTheMask() {
        val original = disc()
        val outlined = IconOutline.apply(original, OutlineMode.ADD, widthPx = 6f, color = Color.RED)
        // Mask covering the right half of the canvas.
        val mask = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        paint.color = Color.BLACK
        Canvas(mask).drawRect(32f, 0f, 64f, 64f, paint)

        val out = IconOutline.eraseOutline(outlined, original, mask)

        // Left half keeps its outline; the right half's outline is gone…
        assertTrue(Color.red(out.getPixel(32 - 23, 32)) > 200)
        assertEquals(0, out.getPixel(32 + 23, 32))
        // …but the icon itself survives inside the masked area.
        assertEquals(Color.BLUE, out.getPixel(32 + 15, 32))
    }

    @Test
    fun none_returnsTheSourceUntouched() {
        val src = disc()
        assertTrue(IconOutline.apply(src, OutlineMode.NONE, 6f, Color.RED) === src)
    }
}
