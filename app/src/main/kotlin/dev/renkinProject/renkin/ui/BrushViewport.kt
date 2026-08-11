package dev.renkinProject.renkin.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * Presentation-only transform for an editable canvas. Stored strokes stay in the canvas' own
 * coordinate space, while this model owns the reversible mapping used by gestures.
 */
internal data class BrushViewport(
    val zoom: Float = 1f,
    val pan: Offset = Offset.Zero
) {
    val isReset: Boolean
        get() = zoom == 1f && pan == Offset.Zero

    /** Converts a viewport-relative brush diameter to the underlying canvas scale. */
    fun contentBrush(screenBrush: Float): Float = screenBrush / zoom

    fun contentPosition(position: Offset, size: IntSize): Offset {
        if (size.width <= 0 || size.height <= 0) return Offset.Zero
        val center = size.center
        val contentPosition = center + (position - center - pan) / zoom
        return Offset(
            (contentPosition.x / size.width).coerceIn(0f, 1f),
            (contentPosition.y / size.height).coerceIn(0f, 1f)
        )
    }

    fun transformed(
        zoomChange: Float,
        previousCentroid: Offset,
        currentCentroid: Offset,
        size: IntSize
    ): BrushViewport {
        if (size.width <= 0 || size.height <= 0) return this
        val nextZoom = (zoom * zoomChange).coerceIn(MIN_BRUSH_ZOOM, MAX_BRUSH_ZOOM)
        if (!previousCentroid.isFinite || !currentCentroid.isFinite) {
            return BrushViewport(nextZoom, pan.clampedFor(nextZoom, size))
        }
        val center = size.center
        // Keep the content point that was below the old centroid below the new centroid. This
        // handles two-finger panning and off-centre pinch zoom with the same calculation.
        val zoomRatio = nextZoom / zoom
        val nextPan = currentCentroid - center -
            (previousCentroid - center - pan) * zoomRatio
        return BrushViewport(nextZoom, nextPan.clampedFor(nextZoom, size))
    }
}

private val IntSize.center: Offset
    get() = Offset(width / 2f, height / 2f)

private val Offset.isFinite: Boolean
    get() = x.isFinite() && y.isFinite()

private fun Offset.clampedFor(zoom: Float, size: IntSize): Offset {
    val maxX = size.width * (zoom - 1f) / 2f
    val maxY = size.height * (zoom - 1f) / 2f
    return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
}

private const val MIN_BRUSH_ZOOM = 1f
private const val MAX_BRUSH_ZOOM = 4f
