package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import dev.renkinProject.renkin.icon.creator.BackgroundBrushOperation
import dev.renkinProject.renkin.icon.creator.BrushAction

/**
 * One brush stroke in normalised canvas coordinates, independent of bitmap resolution or editor.
 * Session-only state holds geometry and intent, never icon pixels.
 */
internal data class BrushStroke(
    val brush: Float,
    val points: List<Offset>,
    val action: BrushAction = BrushAction.ERASE
)

/** Rasterises [strokes] into an antialiased alpha mask. */
internal fun buildEraseMask(strokes: List<BrushStroke>, size: Int = 256): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { stroke ->
        val width = stroke.brush * size
        if (stroke.points.size < 2) {
            paint.style = Paint.Style.FILL
            val point = stroke.points.firstOrNull() ?: return@forEach
            canvas.drawCircle(point.x * size, point.y * size, width / 2f, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = width
            val path = android.graphics.Path()
            stroke.points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.x * size, point.y * size)
                else path.lineTo(point.x * size, point.y * size)
            }
            canvas.drawPath(path, paint)
        }
    }
    return bitmap
}

/**
 * Preserves action order while coalescing adjacent strokes that perform the same operation.
 * This keeps repeated brushing cheap without making one action permanently win every overlap.
 */
internal fun buildBackgroundBrushOperations(
    strokes: List<BrushStroke>,
    size: Int = 256
): List<BackgroundBrushOperation> {
    if (strokes.isEmpty()) return emptyList()
    val groups = mutableListOf<MutableList<BrushStroke>>()
    strokes.forEach { stroke ->
        val current = groups.lastOrNull()
        if (current != null && current.first().action == stroke.action) {
            current += stroke
        } else {
            groups += mutableListOf(stroke)
        }
    }
    return groups.map { group ->
        BackgroundBrushOperation(
            action = group.first().action,
            mask = buildEraseMask(group, size)
        )
    }
}
