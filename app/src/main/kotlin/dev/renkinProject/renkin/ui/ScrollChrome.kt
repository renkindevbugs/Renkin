package dev.renkinProject.renkin.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Mihon-style scroll chrome: a header that overlays scroll-under content instead of stacking
// above it, plus a transient scrollbar for long lists and grids.

/**
 * Lays [content] out at full size with [header] drawn on top of it (Mihon's Scaffold approach):
 * the content receives the header's current height as top [PaddingValues] each frame, so a
 * collapsing app bar never changes the content's own size constraints — lists scroll under the
 * header rather than being squeezed below it, which keeps flings smooth while the bar animates.
 * Lazy content should apply the padding as contentPadding; static content as Modifier.padding.
 * The header is responsible for its own opaque background.
 */
@Composable
fun OverlayHeaderLayout(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val headerPlaceables = subcompose(OverlaySlot.HEADER, header).map { it.measure(loose) }
        val headerHeight = headerPlaceables.maxOfOrNull { it.height } ?: 0
        val contentPlaceables = subcompose(OverlaySlot.CONTENT) {
            content(PaddingValues(top = headerHeight.toDp()))
        }.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Content first so the header draws over it.
            contentPlaceables.forEach { it.place(0, 0) }
            headerPlaceables.forEach { it.place(0, 0) }
        }
    }
}

private enum class OverlaySlot { HEADER, CONTENT }

/**
 * Draws a thin transient scrollbar along the right edge of a lazy list, sized and positioned by
 * estimating total content height from the average visible item size (exact totals are unknowable
 * in a lazy layout). Appears while scrolling, fades out shortly after. [topInset] shifts the track
 * below an overlaid header so the thumb is never hidden under it.
 */
@Composable
fun Modifier.drawVerticalScrollbar(state: LazyListState, topInset: Dp = 0.dp): Modifier {
    val alpha by scrollbarAlpha(state.isScrollInProgress)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val insetPx = with(androidx.compose.ui.platform.LocalDensity.current) { topInset.toPx() }
    return drawWithContent {
        drawContent()
        if (alpha == 0f) return@drawWithContent
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty() || info.totalItemsCount <= items.size) return@drawWithContent
        val avgItem = items.sumOf { it.size } / items.size.toFloat()
        if (avgItem <= 0f) return@drawWithContent
        val totalHeight = avgItem * info.totalItemsCount
        val scrolled = state.firstVisibleItemIndex * avgItem + state.firstVisibleItemScrollOffset
        drawScrollbarThumb(alpha, color, insetPx, totalHeight, scrolled)
    }
}

/** Grid variant of [drawVerticalScrollbar]; [spanCount] is the grid's fixed column count. */
@Composable
fun Modifier.drawVerticalScrollbar(state: LazyGridState, spanCount: Int, topInset: Dp = 0.dp): Modifier {
    val alpha by scrollbarAlpha(state.isScrollInProgress)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val insetPx = with(androidx.compose.ui.platform.LocalDensity.current) { topInset.toPx() }
    return drawWithContent {
        drawContent()
        if (alpha == 0f) return@drawWithContent
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty() || info.totalItemsCount <= items.size) return@drawWithContent
        val visibleRows = items.map { it.index / spanCount }.distinct().size
        val avgRow = items.maxOf { it.offset.y + it.size.height }.minus(items.first().offset.y) / visibleRows.toFloat()
        if (avgRow <= 0f) return@drawWithContent
        val totalRows = (info.totalItemsCount + spanCount - 1) / spanCount
        val totalHeight = avgRow * totalRows
        val scrolled = (state.firstVisibleItemIndex / spanCount) * avgRow + state.firstVisibleItemScrollOffset
        drawScrollbarThumb(alpha, color, insetPx, totalHeight, scrolled)
    }
}

/** Shared fade: quick in while scrolling, delayed ease-out once the scroll settles. */
@Composable
private fun scrollbarAlpha(scrollInProgress: Boolean) = animateFloatAsState(
    targetValue = if (scrollInProgress) 1f else 0f,
    animationSpec = if (scrollInProgress) tween(durationMillis = 75) else tween(durationMillis = 300, delayMillis = 700),
    label = "scrollbarAlpha"
)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScrollbarThumb(
    alpha: Float,
    color: androidx.compose.ui.graphics.Color,
    topInsetPx: Float,
    totalContentHeight: Float,
    scrolledPast: Float
) {
    val trackHeight = size.height - topInsetPx
    if (totalContentHeight <= trackHeight) return
    val minThumb = 24.dp.toPx()
    val thumbHeight = (trackHeight * (trackHeight / totalContentHeight)).coerceAtLeast(minThumb)
    val maxOffset = totalContentHeight - trackHeight
    val fraction = (scrolledPast / maxOffset).coerceIn(0f, 1f)
    val thumbY = topInsetPx + fraction * (trackHeight - thumbHeight)
    val thumbWidth = 4.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - thumbWidth - 2.dp.toPx(), thumbY),
        size = Size(thumbWidth, thumbHeight),
        cornerRadius = CornerRadius(thumbWidth / 2f),
        alpha = 0.5f * alpha
    )
}
