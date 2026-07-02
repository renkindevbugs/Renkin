@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package dev.alembiconsProject.alembicons.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.extension.contentBounds
import dev.alembiconsProject.alembicons.ui.theme.CardShape

/** Colours for the blueprint canvas; filled from either a fixed navy palette or the M3 scheme. */
private data class BlueprintColors(
    val background: Color,
    val grid: Color,
    val frame: Color,
    val line: Color,
    val box: Color,
    val hatch: Color
)

/**
 * Visual positioning tool (opened from the Modifier tab's Adjustments), drawn like a technical
 * blueprint: the icon's content bounding box is hatched, and double-headed measurement arrows on the
 * horizontal / vertical axes show the pixel distance from the artwork to each canvas edge (real
 * bitmap pixels, so the numbers match the export). The offsets are the only position mechanism:
 * switching auto-centre on computes the slider values that centre the artwork (both axes), and
 * dragging a slider manually flips the switch back off.
 */
@Composable
internal fun CenterDialog(
    iconBitmap: Bitmap?,
    autoCenter: Boolean,
    offsetX: Float,
    offsetY: Float,
    onAutoCenterChange: (Boolean) -> Unit,
    onOffsetXChange: (Float) -> Unit,
    onOffsetYChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val bounds = remember(iconBitmap) { iconBitmap?.contentBounds() }

    val colors = BlueprintColors(
        background = MaterialTheme.colorScheme.surfaceVariant,
        grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
        frame = MaterialTheme.colorScheme.outline,
        line = MaterialTheme.colorScheme.onSurfaceVariant,
        box = MaterialTheme.colorScheme.primary,
        hatch = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    )
    val textMeasurer = rememberTextMeasurer()

    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.position)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(CardShape)
                        .background(colors.background)
                ) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        )
                        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                            drawBlueprint(iconBitmap, bounds, colors, textMeasurer)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.centerIcon),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = autoCenter,
                        onCheckedChange = { on ->
                            onAutoCenterChange(on)
                            // Auto-centre only computes the sliders: nudge the current offsets by
                            // whatever is needed to put the content box in the middle. The sliders
                            // move to show it, and the pipeline stays offset-only.
                            if (on && iconBitmap != null && bounds != null) {
                                val dx = ((iconBitmap.width - bounds.width()) / 2f - bounds.left) / iconBitmap.width
                                val dy = ((iconBitmap.height - bounds.height()) / 2f - bounds.top) / iconBitmap.height
                                onOffsetXChange((offsetX + dx).coerceIn(-0.5f, 0.5f))
                                onOffsetYChange((offsetY + dy).coerceIn(-0.5f, 0.5f))
                            }
                        }
                    )
                }

                Text(
                    text = stringResource(R.string.positionHorizontal),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = offsetX,
                    onValueChange = {
                        onOffsetXChange(it)
                        // A manual nudge means the user takes over — auto-centre no longer holds.
                        onAutoCenterChange(false)
                    },
                    valueRange = -0.5f..0.5f,
                    track = { SliderDefaults.CenteredTrack(sliderState = it) }
                )
                Text(
                    text = stringResource(R.string.positionVertical),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = offsetY,
                    onValueChange = {
                        onOffsetYChange(it)
                        onAutoCenterChange(false)
                    },
                    valueRange = -0.5f..0.5f,
                    track = { SliderDefaults.CenteredTrack(sliderState = it) }
                )
            }
        }
    )
}

/**
 * The blueprint overlay: grid + frame, hatched content box with its pixel size badged in the middle,
 * and double-headed arrows with pixel distances from the box to each edge (skipped when the artwork
 * already touches that edge). All numbers are bitmap pixels — what actually lands in the built APK —
 * not canvas dp.
 */
private fun DrawScope.drawBlueprint(
    bitmap: Bitmap,
    bounds: android.graphics.Rect?,
    colors: BlueprintColors,
    textMeasurer: TextMeasurer
) {
    val thin = 1.dp.toPx()

    // Grid (8 divisions) + outer frame.
    for (i in 1 until 8) {
        val p = size.width * i / 8f
        drawLine(colors.grid, Offset(p, 0f), Offset(p, size.height), thin)
        drawLine(colors.grid, Offset(0f, p), Offset(size.width, p), thin)
    }
    drawRect(colors.frame, style = Stroke(1.5.dp.toPx()))

    if (bounds == null) return
    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()
    val l = bounds.left / bw * size.width
    val t = bounds.top / bh * size.height
    val r = bounds.right / bw * size.width
    val b = bounds.bottom / bh * size.height
    val cx = (l + r) / 2f
    val cy = (t + b) / 2f
    val labelStyle = TextStyle(color = colors.line, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

    // Hatched content box.
    clipRect(l, t, r, b) {
        val step = 7.dp.toPx()
        var x = l - (b - t)
        while (x < r) {
            drawLine(colors.hatch, Offset(x, b), Offset(x + (b - t), t), thin)
            x += step
        }
    }
    drawRect(colors.box, Offset(l, t), Size(r - l, b - t), style = Stroke(1.5.dp.toPx()))

    // Measurement arrows through the content centre, one per edge the artwork doesn't touch.
    val left = bounds.left
    val right = bitmap.width - bounds.right
    val top = bounds.top
    val bottom = bitmap.height - bounds.bottom
    if (left > 0) hArrow(0f, l, cy, "$left", colors.line, labelStyle, textMeasurer)
    if (right > 0) hArrow(r, size.width, cy, "$right", colors.line, labelStyle, textMeasurer)
    if (top > 0) vArrow(0f, t, cx, "$top", colors.line, labelStyle, textMeasurer)
    if (bottom > 0) vArrow(b, size.height, cx, "$bottom", colors.line, labelStyle, textMeasurer)

    // Content size badge in the middle of the box.
    val sizeText = textMeasurer.measure(AnnotatedString("${bounds.width()}×${bounds.height()}"), labelStyle)
    val padH = 6.dp.toPx()
    val padV = 3.dp.toPx()
    drawRoundRect(
        colors.background,
        Offset(cx - sizeText.size.width / 2f - padH, cy - sizeText.size.height / 2f - padV),
        Size(sizeText.size.width + padH * 2, sizeText.size.height + padV * 2),
        CornerRadius(4.dp.toPx())
    )
    drawRoundRect(
        colors.line,
        Offset(cx - sizeText.size.width / 2f - padH, cy - sizeText.size.height / 2f - padV),
        Size(sizeText.size.width + padH * 2, sizeText.size.height + padV * 2),
        CornerRadius(4.dp.toPx()),
        style = Stroke(thin)
    )
    drawText(sizeText, topLeft = Offset(cx - sizeText.size.width / 2f, cy - sizeText.size.height / 2f))
}

/** Horizontal double-headed arrow from [x1] to [x2] at height [y], its pixel label above the middle. */
private fun DrawScope.hArrow(
    x1: Float, x2: Float, y: Float,
    label: String, color: Color, style: TextStyle, measurer: TextMeasurer
) {
    val thin = 1.dp.toPx()
    val head = 4.dp.toPx()
    drawLine(color, Offset(x1, y), Offset(x2, y), thin)
    drawLine(color, Offset(x1, y), Offset(x1 + head, y - head), thin)
    drawLine(color, Offset(x1, y), Offset(x1 + head, y + head), thin)
    drawLine(color, Offset(x2, y), Offset(x2 - head, y - head), thin)
    drawLine(color, Offset(x2, y), Offset(x2 - head, y + head), thin)
    val text = measurer.measure(AnnotatedString(label), style)
    drawText(text, topLeft = Offset((x1 + x2) / 2f - text.size.width / 2f, y - text.size.height - 2.dp.toPx()))
}

/** Vertical double-headed arrow from [y1] to [y2] at [x], its pixel label beside the middle. */
private fun DrawScope.vArrow(
    y1: Float, y2: Float, x: Float,
    label: String, color: Color, style: TextStyle, measurer: TextMeasurer
) {
    val thin = 1.dp.toPx()
    val head = 4.dp.toPx()
    drawLine(color, Offset(x, y1), Offset(x, y2), thin)
    drawLine(color, Offset(x, y1), Offset(x - head, y1 + head), thin)
    drawLine(color, Offset(x, y1), Offset(x + head, y1 + head), thin)
    drawLine(color, Offset(x, y2), Offset(x - head, y2 - head), thin)
    drawLine(color, Offset(x, y2), Offset(x + head, y2 - head), thin)
    val text = measurer.measure(AnnotatedString(label), style)
    drawText(text, topLeft = Offset(x + 5.dp.toPx(), (y1 + y2) / 2f - text.size.height / 2f))
}
