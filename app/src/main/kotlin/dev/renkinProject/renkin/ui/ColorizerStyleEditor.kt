@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.normalizeGradientAngle
import dev.renkinProject.renkin.icon.creator.snapGradientAngle
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Shared editor for a colourizer style. The caller owns the style, which keeps this usable for
 * per-icon drafts, profile preferences and future preset/selective-colourize surfaces.
 */
@Composable
internal fun ColorizerStyleEditor(
    style: ColorizerStyle,
    onStyleChange: (ColorizerStyle) -> Unit,
    sampleBitmap: Bitmap? = null,
    showSingleColorEffects: Boolean = true
) {
    var pickerTarget by remember { mutableStateOf<ColorPickerTarget?>(null) }
    var displayedAngle by remember(style.gradientAngle) {
        mutableFloatStateOf(normalizeGradientAngle(style.gradientAngle))
    }

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ColorizerMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = style.mode == mode,
                onClick = { onStyleChange(style.copy(mode = mode)) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = ColorizerMode.entries.size
                )
            ) {
                Text(
                    stringResource(
                        if (mode == ColorizerMode.SINGLE_COLOR) {
                            R.string.colorizerSingleColor
                        } else {
                            R.string.colorizerGradient
                        }
                    )
                )
            }
        }
    }

    ColorizerColorRow(
        label = stringResource(
            if (style.mode == ColorizerMode.SINGLE_COLOR) {
                R.string.iconColor
            } else {
                R.string.gradientFirstColor
            }
        ),
        color = Color(
            if (style.mode == ColorizerMode.GRADIENT) opaque(style.firstColor)
            else style.firstColor
        ),
        onClick = { pickerTarget = ColorPickerTarget.FIRST }
    )

    AnimatedVisibility(visible = style.mode == ColorizerMode.GRADIENT) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ColorizerColorRow(
                    label = stringResource(R.string.gradientSecondColor),
                    color = Color(opaque(style.secondColor)),
                    onClick = { pickerTarget = ColorPickerTarget.SECOND },
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        onStyleChange(
                            style.copy(
                                firstColor = style.secondColor,
                                secondColor = style.firstColor
                            )
                        )
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.SwapHoriz,
                        contentDescription = stringResource(R.string.swapGradientColors)
                    )
                }
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                GradientType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = style.gradientType == type,
                        onClick = { onStyleChange(style.copy(gradientType = type)) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = GradientType.entries.size
                        )
                    ) {
                        Text(
                            stringResource(
                                if (type == GradientType.LINEAR) {
                                    R.string.gradientLinear
                                } else {
                                    R.string.gradientRadial
                                }
                            )
                        )
                    }
                }
            }
            AnimatedVisibility(visible = style.gradientType == GradientType.LINEAR) {
                GradientAngleControl(
                    angle = displayedAngle,
                    onAngleChange = {
                        displayedAngle = it
                        onStyleChange(style.copy(gradientAngle = it))
                    }
                )
            }
        }
    }

    AnimatedVisibility(
        visible = style.mode == ColorizerMode.SINGLE_COLOR && showSingleColorEffects
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ColorizerSwitchRow(
                label = stringResource(R.string.colorizeSolid),
                hint = stringResource(R.string.colorizeSolidHint),
                checked = style.flat,
                onCheckedChange = {
                    onStyleChange(style.copy(flat = it, monochrome = if (it) false else style.monochrome))
                }
            )
            ColorizerSwitchRow(
                label = stringResource(R.string.colorizeMonochrome),
                hint = stringResource(R.string.colorizeMonochromeHint),
                checked = style.monochrome,
                onCheckedChange = {
                    onStyleChange(style.copy(monochrome = it, flat = if (it) false else style.flat))
                }
            )
            ColorizerSwitchRow(
                label = stringResource(R.string.inverseColors),
                checked = style.inverse,
                onCheckedChange = { onStyleChange(style.copy(inverse = it)) }
            )
        }
    }

    pickerTarget?.let { target ->
        ColorDialog(
            onDismiss = { pickerTarget = null },
            currentlySelected = Color(
                if (style.mode == ColorizerMode.GRADIENT) {
                    opaque(
                        if (target == ColorPickerTarget.FIRST) {
                            style.firstColor
                        } else {
                            style.secondColor
                        }
                    )
                } else {
                    style.firstColor
                }
            ),
            onColorSelected = { selected ->
                val selectedArgb = selected.toArgb().let {
                    if (style.mode == ColorizerMode.GRADIENT) opaque(it) else it
                }
                onStyleChange(
                    if (target == ColorPickerTarget.FIRST) {
                        style.copy(firstColor = selectedArgb)
                    } else {
                        style.copy(secondColor = selectedArgb)
                    }
                )
            },
            sampleBitmap = sampleBitmap
        )
    }
}

@Composable
private fun ColorizerColorRow(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OptionCard(
        label = label,
        onClick = onClick,
        modifier = modifier,
        trailing = {
            Surface(
                shape = CircleShape,
                color = color,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.size(28.dp)
            ) {}
        }
    )
}

@Composable
private fun ColorizerSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    hint: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private enum class ColorPickerTarget { FIRST, SECOND }

private fun opaque(color: Int): Int = color or 0xFF000000.toInt()

/** Presets a user actually reaches for; anything else comes from dragging the dial. */
private val AnglePresets = listOf(0, 45, 90, 135, 180, 270)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GradientAngleControl(
    angle: Float,
    onAngleChange: (Float) -> Unit
) {
    val angleLabel = stringResource(R.string.gradientAngle)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = angleLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AngleDial(
                angle = angle,
                contentDescription = angleLabel,
                onAngleChange = onAngleChange
            )
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnglePresets.forEach { preset ->
                    FilterChip(
                        selected = angle.roundToInt() == preset,
                        onClick = { onAngleChange(preset.toFloat()) },
                        label = { Text("$preset°") }
                    )
                }
            }
        }
    }
}

@Composable
private fun AngleDial(
    angle: Float,
    contentDescription: String,
    onAngleChange: (Float) -> Unit
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val trackColor = MaterialTheme.colorScheme.secondaryContainer
    val activeColor = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    val thumbBorderColor = MaterialTheme.colorScheme.surface
    val currentOnAngleChange by rememberUpdatedState(onAngleChange)

    fun angleFor(position: Offset, center: Offset): Float {
        val degrees = Math.toDegrees(
            atan2(
                (position.x - center.x).toDouble(),
                (center.y - position.y).toDouble()
            )
        ).toFloat()
        return snapGradientAngle(degrees)
    }

    Box(contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(120.dp)
                .semantics {
                    this.contentDescription = contentDescription
                    progressBarRangeInfo = ProgressBarRangeInfo(angle, 0f..360f)
                    setProgress {
                        currentOnAngleChange(normalizeGradientAngle(it))
                        true
                    }
                }
                // The callback changes on every live angle recomposition. A stable key keeps this
                // pointer coroutine alive for the entire gesture instead of restarting mid-drag.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // At the Main pass, descendants receive events before ancestors. Consuming
                        // here prevents the surrounding verticalScroll from claiming the drag while
                        // this pointer remains captured, including after it leaves the dial bounds.
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Main
                        )
                        down.consume()
                        val center = Offset(size.width / 2f, size.height / 2f)
                        currentOnAngleChange(angleFor(down.position, center))
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.first()
                            if (change.pressed) {
                                currentOnAngleChange(angleFor(change.position, center))
                            }
                            change.consume()
                        } while (change.pressed)
                    }
                }
        ) {
            val outerRadius = size.minDimension / 2f
            val trackWidth = 14.dp.toPx()
            val trackRadius = outerRadius - trackWidth / 2f - 4.dp.toPx()

            drawCircle(color = containerColor, radius = outerRadius)
            drawCircle(
                color = trackColor,
                radius = trackRadius,
                style = Stroke(width = trackWidth)
            )
            if (angle > 0f) {
                val arcInset = outerRadius - trackRadius
                drawArc(
                    color = activeColor,
                    // Canvas sweeps from 3 o'clock; the dial reads 0° at 12 o'clock.
                    startAngle = -90f,
                    sweepAngle = angle,
                    useCenter = false,
                    topLeft = Offset(arcInset, arcInset),
                    size = Size(trackRadius * 2f, trackRadius * 2f),
                    style = Stroke(width = trackWidth, cap = StrokeCap.Round)
                )
            }
            for (tick in 0 until 360 step 45) {
                val radians = Math.toRadians(tick.toDouble())
                val direction = Offset(sin(radians).toFloat(), -cos(radians).toFloat())
                val tickOuter = outerRadius - 1.dp.toPx()
                drawLine(
                    color = tickColor,
                    start = center + direction * (tickOuter - 4.dp.toPx()),
                    end = center + direction * tickOuter,
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            val radians = Math.toRadians(angle.toDouble())
            val thumbCenter = Offset(
                x = center.x + sin(radians).toFloat() * trackRadius,
                y = center.y - cos(radians).toFloat() * trackRadius
            )
            drawCircle(color = thumbBorderColor, radius = 13.dp.toPx(), center = thumbCenter)
            drawCircle(color = activeColor, radius = 10.dp.toPx(), center = thumbCenter)
        }
        Text(
            text = "${angle.roundToInt()}°",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
