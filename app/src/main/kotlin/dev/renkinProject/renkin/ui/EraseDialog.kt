package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
 * The outline eraser (opened from the Modifier tab's Outline section), on the Position
 * tool's blueprint-style canvas. LIVE: each finished stroke commits straight into the
 * adjustments (via [onStrokesChange]), the preview pipeline regenerates and [iconBitmap]
 * recomposes with the outline actually erased — the translucent stroke marker only exists
 * while the finger is down. Done keeps the result; Dismiss restores the strokes the dialog
 * opened with.
 */
@Composable
internal fun EraseDialog(
    iconBitmap: Bitmap?,
    strokes: List<EraseStroke>,
    onStrokesChange: (List<EraseStroke>) -> Unit,
    // True while the preview pipeline regenerates — a stroke was just committed.
    generating: Boolean = false,
    onDismiss: () -> Unit
) {
    // For Dismiss (cancel): the strokes as they were when the dialog opened.
    val openingStrokes = remember { strokes }
    // The gesture handlers below live inside pointerInput and would otherwise capture the
    // strokes list from THEIR composition — every commit would build on that stale list,
    // silently dropping the strokes added since (the "second stroke undoes the first" bug).
    val liveStrokes by rememberUpdatedState(strokes)
    var brush by remember { mutableFloatStateOf(0.10f) }
    // The in-progress stroke, drawn as a translucent marker only until the finger lifts —
    // then it commits into the adjustments and the real erased preview takes over.
    var currentStroke by remember { mutableStateOf<EraseStroke?>(null) }

    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val frameColor = MaterialTheme.colorScheme.outline
    val markerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.4f)

    RenkinAlertDialog(
        onDismissRequest = {
            onStrokesChange(openingStrokes)
            onDismiss()
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.eraseTitle), modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { onStrokesChange(strokes.dropLast(1)) },
                    enabled = strokes.isNotEmpty()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.eraseUndo))
                }
                IconButton(
                    onClick = { onStrokesChange(emptyList()) },
                    enabled = strokes.isNotEmpty()
                ) {
                    Icon(Icons.Filled.DeleteSweep, stringResource(R.string.eraseClear))
                }
            }
        },
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
                    if (generating) {
                        CircularProgressIndicator(
                            strokeWidth = 2.5.dp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                                .size(20.dp)
                        )
                    }
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .pointerInput(brush) {
                                detectDragGestures(
                                    onDragStart = { position ->
                                        currentStroke = EraseStroke(
                                            brush,
                                            listOf(Offset(position.x / size.width, position.y / size.height))
                                        )
                                    },
                                    onDrag = { change, _ ->
                                        val stroke = currentStroke ?: return@detectDragGestures
                                        val point = Offset(
                                            (change.position.x / size.width).coerceIn(0f, 1f),
                                            (change.position.y / size.height).coerceIn(0f, 1f)
                                        )
                                        currentStroke = stroke.copy(points = stroke.points + point)
                                    },
                                    onDragEnd = {
                                        currentStroke?.let { onStrokesChange(liveStrokes + it) }
                                        currentStroke = null
                                    },
                                    onDragCancel = { currentStroke = null }
                                )
                            }
                            .pointerInput(brush) {
                                detectTapGestures { position ->
                                    onStrokesChange(
                                        liveStrokes + EraseStroke(
                                            brush,
                                            listOf(Offset(position.x / size.width, position.y / size.height))
                                        )
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

                        currentStroke?.let { stroke ->
                            val width = stroke.brush * size.width
                            if (stroke.points.size < 2) {
                                val p = stroke.points.firstOrNull() ?: return@let
                                drawCircle(markerColor, width / 2f, Offset(p.x * size.width, p.y * size.height))
                            } else {
                                val path = Path()
                                stroke.points.forEachIndexed { index, point ->
                                    val x = point.x * size.width
                                    val y = point.y * size.height
                                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(
                                    path,
                                    markerColor,
                                    style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                            }
                        }
                    }
                }

                LabeledSlider(
                    label = stringResource(R.string.eraseBrush),
                    value = brush,
                    onValueChange = { brush = it },
                    valueRange = 0.03f..0.25f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            TextButton(onClick = {
                onStrokesChange(openingStrokes)
                onDismiss()
            }) { Text(stringResource(R.string.dismiss)) }
        }
    )
}
