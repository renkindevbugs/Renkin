package dev.renkinProject.renkin.drawable

import android.app.Application

import android.graphics.Bitmap
import android.graphics.Color
import dev.renkinProject.renkin.extension.getBytes
import dev.renkinProject.renkin.extension.scaleFromCenter
import android.graphics.Canvas
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveIconPackDrawableTest {

    private fun layer(color: Int): ByteArray = Bitmap.createBitmap(
        AdaptiveIconPackDrawable.LAYER_SIZE,
        AdaptiveIconPackDrawable.LAYER_SIZE,
        Bitmap.Config.ARGB_8888
    ).apply { eraseColor(color) }.getBytes(Bitmap.CompressFormat.PNG, 100)

    @Test
    fun damagedLayerDecodesAsTransparentInsteadOfCrashing() {
        val icon = AdaptiveIconPackDrawable(byteArrayOf(1, 2, 3), layer(Color.BLUE))

        val foreground = icon.foreground

        assertEquals(AdaptiveIconPackDrawable.LAYER_SIZE, foreground.width)
        assertEquals(Color.TRANSPARENT, foreground.getPixel(0, 0))
        assertEquals(Color.BLUE, icon.background.getPixel(0, 0))
    }

    @Test
    fun withForegroundKeepsMonochromeUnlessANewOneIsGiven() {
        val icon = AdaptiveIconPackDrawable(layer(Color.RED), layer(Color.BLUE), layer(Color.WHITE))
        val replacement = Bitmap.createBitmap(
            AdaptiveIconPackDrawable.LAYER_SIZE,
            AdaptiveIconPackDrawable.LAYER_SIZE,
            Bitmap.Config.ARGB_8888
        ).apply { eraseColor(Color.GREEN) }

        val kept = icon.withForeground(replacement)
        val swapped = icon.withForeground(replacement, replacement)

        assertEquals(Color.GREEN, kept.foreground.getPixel(0, 0))
        assertEquals(Color.WHITE, checkNotNull(kept.monochrome).getPixel(0, 0))
        assertEquals(Color.GREEN, checkNotNull(swapped.monochrome).getPixel(0, 0))
    }
    @Test
    fun previewRendersAtRequestedSizeWhileExportRemainsFullResolution() {
        val icon = AdaptiveIconPackDrawable(layer(Color.RED), layer(Color.BLUE))
        val payload = icon.toDbString()
        val thumbnail = icon.previewBitmap(96)
        assertEquals(96, thumbnail.width)
        assertEquals(96, thumbnail.height)
        assertEquals(Color.RED, thumbnail.getPixel(48, 48))
        assertEquals(96, icon.toBrowserPreviewBitmap(96).width)
        assertEquals(500, icon.toBitmap().width)
        assertEquals(750, icon.foreground.width)
        assertEquals(payload, icon.toDbString())
    }

    @Test
    fun directPreviewMatchesFormerViewportCompensation() {
        val foreground = Bitmap.createBitmap(750, 750, Bitmap.Config.ARGB_8888)
        Canvas(foreground).drawRect(280f, 280f, 470f, 470f,
            android.graphics.Paint().apply { color = Color.RED })
        val icon = AdaptiveIconPackDrawable(foreground.getBytes(Bitmap.CompressFormat.PNG, 100), layer(Color.BLUE))
        val previous = icon.toBitmap().scaleFromCenter(ADAPTIVE_ICON_SCALE)
        val current = icon.previewBitmap(500)
        for (y in 100..400 step 30) for (x in 100..400 step 30) {
            assertEquals("Pixel $x,$y", previous.getPixel(x, y), current.getPixel(x, y))
        }
    }

    @Test
    fun repeatedDrawUsesCachedRasterInsteadOfRetainingBitmapOnDrawable() {
        val icon = AdaptiveIconPackDrawable(layer(Color.RED), layer(Color.BLUE))
        val key = IconPreviewCacheKey(icon, 96)
        icon.setBounds(0, 0, 96, 96)
        val canvas = Canvas(Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888))
        icon.draw(canvas)
        val first = IconPreviewCache.get(key)
        assertTrue(first != null)
        icon.draw(canvas)
        assertSame(first, IconPreviewCache.get(key))
        assertNotSame(icon.toBitmap(), icon.toBitmap())
    }

    @Test
    fun defaultPreviewDownscalingPreservesRectangularArtwork() {
        val icon = BitmapIconDrawable(Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888))
        val list = icon.previewBitmap(50)
        val browser = icon.toBrowserPreviewBitmap(50)
        assertEquals(50, list.width)
        assertEquals(25, list.height)
        assertEquals(list.width, browser.width)
        assertEquals(list.height, browser.height)
        assertEquals(200, icon.toBitmap().width)
    }

}
