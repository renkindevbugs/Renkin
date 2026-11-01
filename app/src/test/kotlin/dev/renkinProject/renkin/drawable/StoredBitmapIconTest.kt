package dev.renkinProject.renkin.drawable

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.renkinProject.renkin.data.editor.EditorBitmapArtwork
import dev.renkinProject.renkin.extension.bitmapFromBase64
import dev.renkinProject.renkin.extension.scaleFromCenter
import dev.renkinProject.renkin.extension.toBase64
import dev.renkinProject.renkin.ui.captureEditorArtwork
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoredBitmapIconTest {
    private fun payload(): String = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.TRANSPARENT)
        for (y in 20 until 140) for (x in 40 until 280) {
            setPixel(x, y, Color.argb(160, x % 256, y, 90))
        }
    }.toBase64(Bitmap.CompressFormat.PNG, 100)

    @Test fun fullReadsRemainExactAndDoNotRetainOrShareMutablePixels() {
        val encoded = payload()
        val expected = bitmapFromBase64(encoded)
        val icon = BitmapIconDrawable.fromStored(encoded, false)
        val base = requireNotNull(icon.detachedStoredCopy())
        val first = icon.toBitmap()
        val second = icon.toBitmap()
        assertNotSame(first, second)
        assertTrue(expected.sameAs(first))
        first.recycle()
        assertTrue(expected.sameAs(second))
        assertTrue(expected.sameAs(base.toBitmap()))
        assertEquals(encoded, icon.toDbString())
        assertEquals(encoded, base.toDbString())
        // Mutating the opt-in Drawable wrapper cannot change the independent base.
        icon.drawable.bitmap.recycle()
        assertTrue(expected.sameAs(base.toBitmap()))
    }

    @Test fun sampledRectangularPreviewsPreserveAspectAndFullExport() {
        val encoded = payload()
        val icon = BitmapIconDrawable.fromStored(encoded, false)
        val small = icon.previewBitmap(80)
        assertEquals(80, small.width)
        assertEquals(40, small.height)
        assertEquals(Color.TRANSPARENT, small.getPixel(0, 0))
        assertTrue(Color.alpha(small.getPixel(40, 20)) > 0)
        assertEquals(320, icon.previewBitmap(640).width)
        small.recycle()
        assertTrue(bitmapFromBase64(encoded).sameAs(icon.toBitmap()))
        assertEquals(320, icon.intrinsicWidth)
        assertEquals(160, icon.intrinsicHeight)
    }

    @Test fun adaptivePreviewZoomDoesNotChangeBrowserOrExportGeometry() {
        val encoded = payload()
        val raw = bitmapFromBase64(encoded)
        val icon = BitmapIconDrawable.fromStored(encoded, true)
        assertTrue(icon.isAdaptiveIcon())
        assertEquals(ADAPTIVE_ICON_SCALE, icon.previewScale)
        assertTrue(raw.sameAs(icon.toBitmap()))
        assertTrue(raw.sameAs(icon.toBrowserPreviewBitmap()))
        assertTrue(raw.scaleFromCenter(ADAPTIVE_ICON_SCALE).sameAs(icon.previewBitmap()))
        val browser = icon.toBrowserPreviewBitmap(80)
        assertEquals(80, browser.width)
        assertEquals(40, browser.height)
        assertEquals(Color.TRANSPARENT, browser.getPixel(0, 0))
    }

    @Test fun editorCaptureKeepsExactPixelsMetadataAndAdaptiveFlags() {
        val encoded = payload()
        val expected = bitmapFromBase64(encoded)
        val icon = BitmapIconDrawable.fromStored(encoded, true)
        val captured = captureEditorArtwork(icon) as EditorBitmapArtwork
        assertEquals(expected.width, captured.width)
        assertEquals(expected.height, captured.height)
        assertEquals(expected.density, captured.density)
        assertEquals(expected.hasAlpha(), captured.hasAlpha)
        assertTrue(captured.adaptive)
        assertEquals(ADAPTIVE_ICON_SCALE, captured.previewScale)
        val expectedPixels = ByteBuffer.allocate(expected.byteCount).also(expected::copyPixelsToBuffer).array()
        assertArrayEquals(expectedPixels, captured.pixels.copyBytes())
        assertEquals(encoded, icon.toDbString())
    }

    @Test fun drawingRespectsBoundsAndAlphaWithoutPoisoningExport() {
        val encoded = payload()
        val icon = BitmapIconDrawable.fromStored(encoded, false)
        val target = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        icon.setBounds(10, 20, 90, 60)
        icon.alpha = 128
        icon.draw(Canvas(target))
        assertEquals(Color.TRANSPARENT, target.getPixel(0, 0))
        assertTrue(Color.alpha(target.getPixel(50, 40)) in 70..90)
        assertTrue(bitmapFromBase64(encoded).sameAs(icon.toBitmap()))
    }
}
