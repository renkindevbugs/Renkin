@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.extension.contentBounds

/** Colours for the blueprint canvas; filled from either a fixed navy palette or the M3 scheme. */
private data class BlueprintColors(
    val background: Color,
    val grid: Color,
    val frame: Color,
    val line: Color,
    val box: Color,
    val hatch: Color
)

internal fun centeredIconOffset(
    frameSize: Int,
    contentStart: Int,
    contentSize: Int,
    scale: Float,
    currentOffset: Float = 0f
): Float {
    val safeScale = scale.takeIf { it > 0f } ?: 1f
    return (
        currentOffset +
            ((frameSize - contentSize) / 2f - contentStart) / frameSize / safeScale
        ).coerceIn(-0.5f, 0.5f)
}

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
    adjustments: AdjustmentState,
    renderPositionBase: (suspend () -> Bitmap?)? = null,
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
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_LAYOUT_DP
    val canvas: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.blueprintFrame(colors.background, colors.frame)) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
                Canvas(Modifier.fillMaxSize()) {
                    drawBlueprint(iconBitmap, bounds, colors, textMeasurer)
                }
            }
        }
    }
    var autoCenterRequest by remember { mutableIntStateOf(0) }
    val currentPositionBase by rememberUpdatedState(renderPositionBase)
    LaunchedEffect(autoCenterRequest) {
        if (autoCenterRequest == 0 || !adjustments.autoCenter) return@LaunchedEffect
        val zeroOffsetBitmap = currentPositionBase?.invoke()
        val bitmap = zeroOffsetBitmap ?: iconBitmap ?: return@LaunchedEffect
        val content = bitmap.contentBounds() ?: return@LaunchedEffect
        if (!adjustments.autoCenter) return@LaunchedEffect

        // Compute absolute offsets from a zero-offset render. Using the visible, already-shifted
        // preview made clipped artwork lose the hidden part of its bounds and converge in steps.
        val currentOffsetX = if (zeroOffsetBitmap != null) 0f else adjustments.iconOffsetX
        val currentOffsetY = if (zeroOffsetBitmap != null) 0f else adjustments.iconOffsetY
        adjustments.iconOffsetX = centeredIconOffset(
            bitmap.width,
            content.left,
            content.width(),
            adjustments.iconScale,
            currentOffsetX
        )
        adjustments.iconOffsetY = centeredIconOffset(
            bitmap.height,
            content.top,
            content.height(),
            adjustments.iconScale,
            currentOffsetY
        )
    }
    val setAutoCenter: (Boolean) -> Unit = { on ->
        adjustments.autoCenter = on
        if (on) autoCenterRequest++
    }
    val autoCenterControl: @Composable (Modifier) -> Unit = { modifier ->
        Row(
            modifier = modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.centerIcon),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = adjustments.autoCenter,
                onCheckedChange = setAutoCenter
            )
        }
    }

    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        modifier = if (wide) {
            Modifier.widthIn(max = POSITION_BLUEPRINT_DIALOG_MAX_WIDTH)
        } else {
            Modifier
        },
        properties = DialogProperties(usePlatformDefaultWidth = !wide),
        title = { Text(stringResource(R.string.position)) },
        confirmButton = {
            if (!wide) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            }
        },
        text = {
            if (wide) {
                BlueprintSideControlLayout(
                    canvas = canvas,
                    sideControl = { modifier ->
                        VerticalLabeledSlider(
                            label = stringResource(R.string.positionVertical),
                            value = adjustments.iconOffsetY,
                            onValueChange = {
                                adjustments.iconOffsetY = it
                                adjustments.autoCenter = false
                            },
                            valueRange = -0.5f..0.5f,
                            modifier = modifier,
                            centered = true,
                            // Moving the thumb upwards should move the icon upwards.
                            reverseValue = true
                        )
                    },
                    bottomControl = {
                        LabeledSlider(
                            label = stringResource(R.string.positionHorizontal),
                            value = adjustments.iconOffsetX,
                            onValueChange = {
                                adjustments.iconOffsetX = it
                                adjustments.autoCenter = false
                            },
                            valueRange = -0.5f..0.5f,
                            centered = true
                        )
                    },
                    footerControl = autoCenterControl,
                    sideFooterControl = { modifier ->
                        TextButton(
                            onClick = onDismiss,
                            modifier = modifier
                        ) {
                            Text(stringResource(R.string.done))
                        }
                    },
                    bottomReservedHeight = 240.dp
                )
            } else {
                BlueprintStackedLayout(canvas = canvas) {
                    autoCenterControl(Modifier.fillMaxWidth())
                    LabeledSlider(
                        label = stringResource(R.string.positionHorizontal),
                        value = adjustments.iconOffsetX,
                        onValueChange = {
                            adjustments.iconOffsetX = it
                            adjustments.autoCenter = false
                        },
                        valueRange = -0.5f..0.5f,
                        centered = true
                    )
                    LabeledSlider(
                        label = stringResource(R.string.positionVertical),
                        value = adjustments.iconOffsetY,
                        onValueChange = {
                            adjustments.iconOffsetY = it
                            adjustments.autoCenter = false
                        },
                        valueRange = -0.5f..0.5f,
                        centered = true
                    )
                }
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

    drawBlueprintGrid(colors.grid)

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
