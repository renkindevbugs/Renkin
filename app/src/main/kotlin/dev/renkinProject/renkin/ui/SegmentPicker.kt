@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.icon.creator.ColorSegment
import dev.renkinProject.renkin.icon.creator.SEGMENT_COUNT_DEFAULT
import dev.renkinProject.renkin.icon.creator.SEGMENT_COUNT_MAX
import dev.renkinProject.renkin.icon.creator.SEGMENT_COUNT_MIN
import dev.renkinProject.renkin.icon.creator.matchesSegment
import dev.renkinProject.renkin.icon.creator.segmentColors
import dev.renkinProject.renkin.ui.theme.DialogShape
import dev.renkinProject.renkin.ui.theme.IconShape as IconTileShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** One selection rendered for the canvas: the picked area, its contour, and how light it is. */
private data class SelectionMasks(
    val area: ImageBitmap,
    val edges: ImageBitmap,
    val meanLuminance: Float
)

/**
 * Region picker for the Colorize segments modifier: the icon with the current pick marked by
 * moving hatching, the regions as chips, then how finely the icon is split and how loosely a
 * pixel may match. Regions are stored as colours, so the pick survives regenerating the icon.
 */
@Composable
internal fun SegmentSelector(
    source: Bitmap,
    targets: List<Int>,
    tolerance: Float,
    onTargetsChange: (List<Int>) -> Unit,
    onToleranceChange: (Float) -> Unit
) {
    var segmentCount by remember { mutableStateOf(SEGMENT_COUNT_DEFAULT) }
    var segments by remember { mutableStateOf<List<ColorSegment>>(emptyList()) }
    var pickerOpen by remember { mutableStateOf(false) }
    var masks by remember { mutableStateOf<SelectionMasks?>(null) }

    // Icons carry transparent margins (adaptive safe zones especially). Cropping to a square
    // around the artwork fills the tile and keeps the overlay aligned with the icon.
    val bounds = remember(source) { artworkBounds(source) }
    val icon = remember(source, bounds) { source.cropped(bounds).asImageBitmap() }

    LaunchedEffect(source, segmentCount) {
        segments = withContext(Dispatchers.Default) { segmentColors(source, segmentCount) }
    }
    LaunchedEffect(source, bounds, targets, tolerance) {
        masks = withContext(Dispatchers.Default) {
            selectionMasks(source.cropped(bounds), targets, tolerance)
        }
    }

    fun toggle(color: Int) {
        onTargetsChange(if (targets.contains(color)) targets - color else targets + color)
    }

    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_LAYOUT_DP

    val canvas: @Composable (Modifier) -> Unit = { canvasModifier ->
        SegmentCanvas(
            icon = icon,
            masks = masks,
            source = source,
            bounds = bounds,
            segments = segments,
            onToggle = ::toggle,
            modifier = canvasModifier
        )
    }
    val controls: @Composable ColumnScope.() -> Unit = {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(segments.size, key = { segments[it].color }) { index ->
                val segment = segments[index]
                FilterChip(
                    selected = targets.contains(segment.color),
                    onClick = { toggle(segment.color) },
                    leadingIcon = {
                        Surface(
                            shape = CircleShape,
                            color = Color(segment.color),
                            modifier = Modifier.size(18.dp)
                        ) {}
                    },
                    label = { Text("${(segment.coverage * 100).roundToInt()}%") }
                )
            }
        }
        LabeledSlider(
            label = stringResource(R.string.segmentCount),
            value = segmentCount.toFloat(),
            onValueChange = { segmentCount = it.roundToInt() },
            valueRange = SEGMENT_COUNT_MIN.toFloat()..SEGMENT_COUNT_MAX.toFloat(),
            valueLabel = "$segmentCount"
        )
        LabeledSlider(
            label = stringResource(R.string.segmentTolerance),
            value = tolerance,
            onValueChange = onToleranceChange,
            valueRange = 0.02f..0.5f,
            valueLabel = "${(tolerance * 100).roundToInt()}"
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (targets.isEmpty()) {
                    stringResource(R.string.segmentNoneSelected)
                } else {
                    stringResource(R.string.segmentSelectedCount, targets.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { pickerOpen = true }) {
                Text(stringResource(R.string.segmentPickOnIcon))
            }
        }
    }

    if (wide) {
        // Wide screens: the icon is the thing being aimed at, so it gets its own column instead
        // of pushing every control below the fold.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            canvas(Modifier.width(WideCanvasSize))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = controls
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            canvas(
                Modifier
                    .fillMaxWidth(0.55f)
                    .align(Alignment.CenterHorizontally)
            )
            controls()
        }
    }

    if (pickerOpen) {
        Dialog(onDismissRequest = { pickerOpen = false }) {
            Surface(shape = DialogShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.segmentPickTitle),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    SegmentCanvas(
                        icon = icon,
                        masks = masks,
                        source = source,
                        bounds = bounds,
                        segments = segments,
                        onToggle = ::toggle,
                        // Capped: on a tablet a full-width dialog would blow the icon up to
                        // half the screen without making it any easier to aim at.
                        modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { pickerOpen = false }) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The icon with the pick marked by marching hatching and a contour. Both are clipped to the
 * selection's own alpha, so a hole in the pick (the white glyph inside a picked background) stays
 * clear and gets its own contour instead of being swallowed by one big rectangle.
 */
@Composable
private fun SegmentCanvas(
    icon: ImageBitmap,
    masks: SelectionMasks?,
    source: Bitmap,
    bounds: Rect?,
    segments: List<ColorSegment>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Same accent as the Position box, which is the app's existing "this is your selection" mark.
    val accent = MaterialTheme.colorScheme.primary
    val alternative = MaterialTheme.colorScheme.inversePrimary
    // A blue mark on a blue segment is invisible; keep whichever tone differs more from it.
    val markColor = masks?.let {
        if (kotlin.math.abs(accent.luminance() - it.meanLuminance) >=
            kotlin.math.abs(alternative.luminance() - it.meanLuminance)
        ) accent else alternative
    } ?: accent

    val marching = rememberInfiniteTransition(label = "segmentHatch")
    // Slow, and it drifts out and back rather than looping in one direction: a gentle wave reads
    // as "this is alive/selected" without pulling the eye off the icon.
    val phase by marching.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "segmentHatchPhase"
    )

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(IconTileShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(source, bounds, segments) {
                detectTapGestures { offset ->
                    val left = bounds?.left ?: 0
                    val top = bounds?.top ?: 0
                    val width = bounds?.width() ?: source.width
                    val height = bounds?.height() ?: source.height
                    val x = left + (offset.x / size.width * width).toInt()
                    val y = top + (offset.y / size.height * height).toInt()
                    if (x !in 0 until source.width || y !in 0 until source.height) {
                        return@detectTapGestures
                    }
                    val pixel = source.getPixel(x, y)
                    if (AndroidColor.alpha(pixel) == 0) return@detectTapGestures
                    // Snap to the segment the pixel belongs to, so a tap on an antialiased edge
                    // still selects a real region.
                    segments.minByOrNull { colorDistance(pixel, it.color) }
                        ?.let { onToggle(it.color) }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
            val target = IntSize(size.width.roundToInt(), size.height.roundToInt())
            drawImage(
                image = icon,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(icon.width, icon.height),
                dstOffset = IntOffset.Zero,
                dstSize = target
            )
            masks ?: return@Canvas
            drawMarchingHatch(masks.area, target, markColor, phase)
            drawImage(
                image = masks.edges,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(masks.edges.width, masks.edges.height),
                dstOffset = IntOffset.Zero,
                dstSize = target,
                colorFilter = ColorFilter.tint(markColor)
            )
        }
    }
}

/** Diagonal stripes drawn into a layer, then cut down to the selection with DstIn. */
private fun DrawScope.drawMarchingHatch(
    area: ImageBitmap,
    target: IntSize,
    color: Color,
    phase: Float
) {
    // Hatching weight: thicker than the Position blueprint's hairlines so it reads at preview
    // size, with enough gap that the artwork underneath stays legible.
    val spacing = 11.dp.toPx()
    val stroke = 2.dp.toPx()
    drawContext.canvas.saveLayer(
        androidx.compose.ui.geometry.Rect(Offset.Zero, Size(size.width, size.height)),
        androidx.compose.ui.graphics.Paint()
    )
    // The stripes run at 45°, so shifting them along x by one spacing per cycle makes them
    // crawl diagonally — the classic "marching ants" cue that this area is selected.
    var x = -size.height + phase * spacing
    while (x <= size.width + size.height) {
        drawLine(
            color = color.copy(alpha = 0.55f),
            // The stripe travels one spacing over the animation's full sweep.
            start = Offset(x, 0f),
            end = Offset(x + size.height, size.height),
            strokeWidth = stroke,
            cap = StrokeCap.Butt
        )
        x += spacing
    }
    drawImage(
        image = area,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(area.width, area.height),
        dstOffset = IntOffset.Zero,
        dstSize = target,
        blendMode = BlendMode.DstIn
    )
    drawContext.canvas.restore()
}

/** Alpha masks of the picked area and its contour, plus how light the picked pixels are. */
private fun selectionMasks(
    source: Bitmap,
    targets: List<Int>,
    tolerance: Float
): SelectionMasks? {
    if (targets.isEmpty()) return null
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    val selected = BooleanArray(width * height)
    var luminanceSum = 0f
    var selectedCount = 0
    for (i in pixels.indices) {
        val pixel = pixels[i]
        val hit = AndroidColor.alpha(pixel) != 0 && matchesSegment(pixel, targets, tolerance)
        selected[i] = hit
        if (hit) {
            luminanceSum += (
                0.213f * AndroidColor.red(pixel) +
                    0.715f * AndroidColor.green(pixel) +
                    0.072f * AndroidColor.blue(pixel)
                ) / 255f
            selectedCount++
        }
    }
    if (selectedCount == 0) return null

    fun selectedAt(x: Int, y: Int): Boolean =
        x in 0 until width && y in 0 until height && selected[y * width + x]

    // The contour is the heavy mark, the hatching is the light one — thick enough to read at
    // preview size, and dashed so it never looks like part of the artwork.
    val thickness = maxOf(2, minOf(width, height) / 45)
    val dashPeriod = maxOf(thickness * 4, minOf(width, height) / 20)
    val areaPixels = IntArray(width * height)
    val edgePixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val i = y * width + x
            if (!selected[i]) continue
            areaPixels[i] = AndroidColor.WHITE
            // A pixel within [thickness] of the boundary belongs to the contour. Every hole in
            // the pick produces its own, so cut-outs read as cut-outs.
            for (offset in 1..thickness) {
                if (!selectedAt(x - offset, y) || !selectedAt(x + offset, y) ||
                    !selectedAt(x, y - offset) || !selectedAt(x, y + offset)
                ) {
                    if (((x + y) / dashPeriod) % 2 == 0) edgePixels[i] = AndroidColor.WHITE
                    break
                }
            }
        }
    }

    return SelectionMasks(
        area = Bitmap.createBitmap(areaPixels, width, height, Bitmap.Config.ARGB_8888)
            .asImageBitmap(),
        edges = Bitmap.createBitmap(edgePixels, width, height, Bitmap.Config.ARGB_8888)
            .asImageBitmap(),
        meanLuminance = luminanceSum / selectedCount
    )
}

/** Square bounds of the visible pixels, so the preview drops an icon's transparent margins. */
private fun artworkBounds(icon: Bitmap): Rect? {
    val width = icon.width
    val height = icon.height
    val pixels = IntArray(width * height)
    icon.getPixels(pixels, 0, width, 0, 0, width, height)
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (AndroidColor.alpha(pixels[y * width + x]) == 0) continue
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    if (right < left || bottom < top) return null

    // Square it: the canvas is square, and a non-square crop would letterbox the overlay out of
    // alignment with the icon underneath.
    val side = maxOf(right - left + 1, bottom - top + 1)
    val centerX = (left + right) / 2
    val centerY = (top + bottom) / 2
    val squareLeft = (centerX - side / 2).coerceIn(0, maxOf(0, width - side))
    val squareTop = (centerY - side / 2).coerceIn(0, maxOf(0, height - side))
    return Rect(
        squareLeft,
        squareTop,
        minOf(width, squareLeft + side),
        minOf(height, squareTop + side)
    )
}

private fun Bitmap.cropped(bounds: Rect?): Bitmap =
    if (bounds == null) this else Bitmap.createBitmap(
        this,
        bounds.left,
        bounds.top,
        bounds.width(),
        bounds.height()
    )

private fun colorDistance(first: Int, second: Int): Int {
    val dr = AndroidColor.red(first) - AndroidColor.red(second)
    val dg = AndroidColor.green(first) - AndroidColor.green(second)
    val db = AndroidColor.blue(first) - AndroidColor.blue(second)
    return dr * dr + dg * dg + db * db
}

/** Canvas width in the side-by-side layout: big enough to tap regions, not the whole pane. */
private val WideCanvasSize = 260.dp
