package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.icon.creator.BrushAction
import kotlinx.coroutines.delay

/**
 * Shared brush editor used by the outline eraser and background correction, on the Position
 * tool's blueprint-style canvas. LIVE: each finished stroke commits straight into the
 * adjustments (via [onStrokesChange]), the preview pipeline regenerates and [iconBitmap]
 * recomposes with the outline actually erased — the translucent stroke marker only exists
 * while the finger is down. Done keeps the result; Dismiss restores the strokes the dialog
 * opened with.
 */
@Composable
internal fun EraseDialog(
    iconBitmap: Bitmap?,
    strokes: List<BrushStroke>,
    onStrokesChange: (List<BrushStroke>) -> Unit,
    // True while the preview pipeline regenerates — a stroke was just committed.
    generating: Boolean = false,
    // Background removal also needs the opposite move: painting the original artwork back where
    // the colour match ate an edge. The outline eraser has nothing to restore, so it stays off.
    allowRestore: Boolean = false,
    @StringRes title: Int = R.string.eraseTitle,
    onDismiss: () -> Unit
) {
    // For Dismiss (cancel): the strokes as they were when the dialog opened.
    val openingStrokes = remember { strokes }
    // The gesture handlers below live inside pointerInput and would otherwise capture the
    // strokes list from THEIR composition — every commit would build on that stale list,
    // silently dropping the strokes added since (the "second stroke undoes the first" bug).
    val liveStrokes by rememberUpdatedState(strokes)
    var brush by remember { mutableFloatStateOf(0.10f) }
    // Bumped on every slider move: the canvas then shows the brush at its real size until the
    // user stops adjusting. Without it the only way to learn the size was to draw and undo.
    var brushPreviewTick by remember { mutableIntStateOf(0) }
    var brushPreviewVisible by remember { mutableStateOf(false) }
    val onBrushChange: (Float) -> Unit = { value ->
        brush = value
        brushPreviewTick++
    }
    LaunchedEffect(brushPreviewTick) {
        if (brushPreviewTick == 0) return@LaunchedEffect
        brushPreviewVisible = true
        delay(BRUSH_PREVIEW_LINGER_MS)
        brushPreviewVisible = false
    }
    // The in-progress stroke, drawn as a translucent marker only until the finger lifts —
    // then it commits into the adjustments and the real erased preview takes over.
    var currentStroke by remember { mutableStateOf<BrushStroke?>(null) }
    var brushAction by remember { mutableStateOf(BrushAction.ERASE) }

    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val frameColor = MaterialTheme.colorScheme.outline
    // Two directions, two colours: red takes pixels away, the theme accent brings them back.
    val eraseMarkerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
    val restoreMarkerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_LAYOUT_DP

    RenkinAlertDialog(
        onDismissRequest = {
            onStrokesChange(openingStrokes)
            onDismiss()
        },
        modifier = if (wide) {
            Modifier.widthIn(max = ERASE_BLUEPRINT_DIALOG_MAX_WIDTH)
        } else {
            Modifier
        },
        properties = DialogProperties(usePlatformDefaultWidth = !wide),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), modifier = Modifier.weight(1f))
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
            val canvas: @Composable (Modifier) -> Unit = { modifier ->
                Box(
                    modifier.blueprintFrame(
                        background = MaterialTheme.colorScheme.surfaceVariant,
                        frame = frameColor
                    )
                ) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
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
                            .fillMaxSize()
                            .pointerInput(brush, brushAction) {
                                detectDragGestures(
                                    onDragStart = { position ->
                                        currentStroke = BrushStroke(
                                            brush = brush,
                                            points = listOf(
                                                Offset(
                                                    position.x / size.width,
                                                    position.y / size.height
                                                )
                                            ),
                                            action = brushAction
                                        )
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val stroke =
                                            currentStroke ?: return@detectDragGestures
                                        val point = Offset(
                                            (change.position.x / size.width).coerceIn(0f, 1f),
                                            (change.position.y / size.height).coerceIn(0f, 1f)
                                        )
                                        currentStroke =
                                            stroke.copy(points = stroke.points + point)
                                    },
                                    onDragEnd = {
                                        currentStroke?.let {
                                            onStrokesChange(liveStrokes + it)
                                        }
                                        currentStroke = null
                                    },
                                    onDragCancel = { currentStroke = null }
                                )
                            }
                            .pointerInput(brush, brushAction) {
                                detectTapGestures { position ->
                                    onStrokesChange(
                                        liveStrokes + BrushStroke(
                                            brush = brush,
                                            points = listOf(
                                                Offset(
                                                    position.x / size.width,
                                                    position.y / size.height
                                                )
                                            ),
                                            action = brushAction
                                        )
                                    )
                                }
                            }
                    ) {
                        drawBlueprintGrid(gridColor)

                        if (brushPreviewVisible && currentStroke == null) {
                            drawBrushSizeGuide(radius = brush * size.width / 2f)
                        }

                        currentStroke?.let { stroke ->
                            val width = stroke.brush * size.width
                            val markerColor = when (stroke.action) {
                                BrushAction.ERASE -> eraseMarkerColor
                                BrushAction.RESTORE -> restoreMarkerColor
                            }
                            if (stroke.points.size < 2) {
                                val p = stroke.points.firstOrNull() ?: return@let
                                drawCircle(
                                    markerColor,
                                    width / 2f,
                                    Offset(p.x * size.width, p.y * size.height)
                                )
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
                                    style = Stroke(
                                        width,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (allowRestore) {
                    // Above the canvas: the mode decides what the next stroke does, so it must be
                    // read before drawing, not found afterwards under the slider.
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = brushAction == BrushAction.ERASE,
                            onClick = { brushAction = BrushAction.ERASE },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.brushErase))
                        }
                        SegmentedButton(
                            selected = brushAction == BrushAction.RESTORE,
                            onClick = { brushAction = BrushAction.RESTORE },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.brushRestore))
                        }
                    }
                }
                if (wide) {
                    BlueprintSideControlLayout(
                        canvas = canvas,
                        sideControl = { modifier ->
                            VerticalLabeledSlider(
                                label = stringResource(R.string.eraseBrush),
                                value = brush,
                                onValueChange = onBrushChange,
                                valueRange = 0.03f..0.25f,
                                modifier = modifier
                            )
                        }
                    )
                } else {
                    BlueprintStackedLayout(canvas = canvas) {
                        LabeledSlider(
                            label = stringResource(R.string.eraseBrush),
                            value = brush,
                            onValueChange = onBrushChange,
                            valueRange = 0.03f..0.25f
                        )
                    }
                }
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

/** A reusable brush footprint that stays legible over both light and dark artwork. */
internal fun DrawScope.drawBrushSizeGuide(radius: Float) {
    val dashLength = 10.dp.toPx()
    val dashGap = 7.dp.toPx()
    val dashEffect = PathEffect.dashPathEffect(
        intervals = floatArrayOf(dashLength, dashGap),
        phase = 0f
    )
    // Matching rounded caps make the two strokes read as one outlined pill per dash.
    drawCircle(
        color = Color.Black.copy(alpha = 0.68f),
        radius = radius,
        center = center,
        style = Stroke(
            width = 4.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = dashEffect
        )
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.85f),
        radius = radius,
        center = center,
        style = Stroke(
            width = 2.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = dashEffect
        )
    )
}

// How long the brush-size ring lingers after the last slider move.
private const val BRUSH_PREVIEW_LINGER_MS = 900L
