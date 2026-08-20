package dev.renkinProject.renkin.icon.creator

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundBrushTest {

    private fun pixel(color: Int): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, color) }

    private fun operation(action: BrushAction, maskAlpha: Int = 255) =
        BackgroundBrushOperation(action, pixel(Color.argb(maskAlpha, 0, 0, 0)))

    @Test
    fun zeroToleranceLeavesAutomaticAndPickedColorRemovalDisabled() {
        val original = pixel(Color.BLUE)

        assertEquals(
            Color.BLUE,
            original.removeMatchedBackground(emptyList(), 0f).getPixel(0, 0)
        )
        assertEquals(
            Color.BLUE,
            original.removeMatchedBackground(listOf(Color.BLUE), 0f).getPixel(0, 0)
        )
    }

    @Test
    fun restoreUsesOriginalArtwork() {
        val result = pixel(Color.TRANSPARENT).applyBackgroundBrush(
            original = pixel(Color.BLUE),
            operations = listOf(operation(BrushAction.RESTORE))
        )

        assertEquals(Color.BLUE, result.getPixel(0, 0))
    }

    @Test
    fun latestOperationWinsAnOverlap() {
        val original = pixel(Color.BLUE)
        val cleaned = pixel(Color.RED)

        val erasedLast = cleaned.applyBackgroundBrush(
            original,
            listOf(operation(BrushAction.RESTORE), operation(BrushAction.ERASE))
        )
        val restoredLast = cleaned.applyBackgroundBrush(
            original,
            listOf(operation(BrushAction.ERASE), operation(BrushAction.RESTORE))
        )

        assertEquals(0, Color.alpha(erasedLast.getPixel(0, 0)))
        assertEquals(Color.BLUE, restoredLast.getPixel(0, 0))
    }

    @Test
    fun erasePreservesAntialiasedMaskAlpha() {
        val result = pixel(Color.RED).applyBackgroundBrush(
            original = pixel(Color.BLUE),
            operations = listOf(operation(BrushAction.ERASE, maskAlpha = 128))
        )

        assertTrue(Color.alpha(result.getPixel(0, 0)) in 120..135)
    }
}
