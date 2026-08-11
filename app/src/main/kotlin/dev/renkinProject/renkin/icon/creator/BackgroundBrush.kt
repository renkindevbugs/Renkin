package dev.renkinProject.renkin.icon.creator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect

enum class BrushAction { ERASE, RESTORE }

/** One ordered background correction. Consecutive strokes of the same action may share a mask. */
data class BackgroundBrushOperation(
    val action: BrushAction,
    val mask: Bitmap
)

/**
 * Applies hand corrections after automatic background removal. Operations stay ordered so the
 * latest stroke wins in an overlap. Bitmap compositing preserves the mask's antialiased edges.
 */
internal fun Bitmap.applyBackgroundBrush(
    original: Bitmap,
    operations: List<BackgroundBrushOperation>
): Bitmap {
    if (operations.isEmpty() || width <= 0 || height <= 0) return this
    if (original.width != width || original.height != height) return this

    val output = copy(Bitmap.Config.ARGB_8888, true)
    output.density = density
    val bounds = Rect(0, 0, width, height)
    val outputCanvas = Canvas(output)
    val filteredPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    operations.forEach { operation ->
        when (operation.action) {
            BrushAction.ERASE -> {
                val erasePaint = Paint(filteredPaint).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                }
                outputCanvas.drawBitmap(operation.mask, null, bounds, erasePaint)
            }

            BrushAction.RESTORE -> {
                val restored = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    density = this@applyBackgroundBrush.density
                }
                val restoredCanvas = Canvas(restored)
                restoredCanvas.drawBitmap(original, null, bounds, filteredPaint)
                val clipPaint = Paint(filteredPaint).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                restoredCanvas.drawBitmap(operation.mask, null, bounds, clipPaint)
                outputCanvas.drawBitmap(restored, 0f, 0f, filteredPaint)
                restored.recycle()
            }
        }
    }
    return output
}
