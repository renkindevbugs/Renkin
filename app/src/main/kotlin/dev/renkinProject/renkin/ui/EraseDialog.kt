package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.theme.CardShape

/**
 * One eraser stroke in NORMALISED canvas coordinates (0..1), so it maps onto any bitmap
 * resolution. Session-only state — strokes hold no icon pixels, just geometry.
 */
internal data class EraseStroke(val brush: Float, val points: List<Offset>)

/** Rasterises [strokes] into an alpha mask: opaque where the outline must be erased. */
internal fun buildEraseMask(strokes: List<EraseStroke>, size: Int = 256): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.BLACK
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    for (stroke in strokes) {
        val width = stroke.brush * size
        if (stroke.points.size < 2) {
            paint.style = Paint.Style.FILL
            val p = stroke.points.firstOrNull() ?: continue
            canvas.drawCircle(p.x * size, p.y * size, width / 2f, paint)
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
 * The outline eraser (opened from the Modifier tab's Outline section): the current preview
 * is shown on the Position tool's blueprint-style canvas and the user paints the areas the
 * outline must skip. Strokes are per-app session state on [AdjustmentState]; Apply hands
 * them back, Cancel discards the edits.
 */
@Composable
internal fun EraseDialog(
    iconBitmap: Bitmap?,
    initialStrokes: List<EraseStroke>,
    onApply: (List<EraseStroke>) -> Unit,
    onDismiss: () -> Unit
) {
    var strokes by remember { mutableStateOf(initialStrokes) }
    var brush by remember { mutableFloatStateOf(0.10f) }

    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val frameColor = MaterialTheme.colorScheme.outline
    val maskColor = MaterialTheme.colorScheme.error.copy(alpha = 0.4f)

    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.eraseTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(CardShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        )
                    }
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .pointerInput(brush) {
                                detectDragGestures(
                                    onDragStart = { position ->
                                        strokes = strokes + EraseStroke(
                                            brush,
                                            listOf(Offset(position.x / size.width, position.y / size.height))
                                        )
                                    },
                                    onDrag = { change, _ ->
                                        val last = strokes.lastOrNull() ?: return@detectDragGestures
                                        val point = Offset(
                                            (change.position.x / size.width).coerceIn(0f, 1f),
                                            (change.position.y / size.height).coerceIn(0f, 1f)
                                        )
                                        strokes = strokes.dropLast(1) + last.copy(points = last.points + point)
                                    }
                                )
                            }
                            .pointerInput(brush) {
                                detectTapGestures { position ->
                                    strokes = strokes + EraseStroke(
                                        brush,
                                        listOf(Offset(position.x / size.width, position.y / size.height))
                                    )
                                }
                            }
                    ) {
                        // The Position tool's technical grid + frame, so the tools feel related.
                        val thin = 1.dp.toPx()
                        for (i in 1 until 8) {
                            val p = size.width * i / 8f
                            drawLine(gridColor, Offset(p, 0f), Offset(p, size.height), thin)
                            drawLine(gridColor, Offset(0f, p), Offset(size.width, p), thin)
                        }
                        drawRect(frameColor, style = Stroke(1.5.dp.toPx()))

                        for (stroke in strokes) {
                            val width = stroke.brush * size.width
                            if (stroke.points.size < 2) {
                                val p = stroke.points.firstOrNull() ?: continue
                                drawCircle(maskColor, width / 2f, Offset(p.x * size.width, p.y * size.height))
                            } else {
                                val path = Path()
                                stroke.points.forEachIndexed { index, point ->
                                    val x = point.x * size.width
                                    val y = point.y * size.height
                                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(
                                    path,
                                    maskColor,
                                    style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        LabeledSlider(
                            label = stringResource(R.string.eraseBrush),
                            value = brush,
                            onValueChange = { brush = it },
                            valueRange = 0.03f..0.25f
                        )
                    }
                    IconButton(onClick = { strokes = strokes.dropLast(1) }, enabled = strokes.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.eraseUndo))
                    }
                    IconButton(onClick = { strokes = emptyList() }, enabled = strokes.isNotEmpty()) {
                        Icon(Icons.Filled.DeleteSweep, stringResource(R.string.eraseClear))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(strokes); onDismiss() }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    )
}
