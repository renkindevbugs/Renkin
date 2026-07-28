@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.MAX_GRADIENT_STOPS
import dev.renkinProject.renkin.icon.creator.MIN_GRADIENT_STOPS
import dev.renkinProject.renkin.icon.creator.normalizeGradientAngle
import dev.renkinProject.renkin.icon.creator.snapGradientAngle
import dev.renkinProject.renkin.extension.toHexString
import dev.renkinProject.renkin.ui.theme.FieldShape
import dev.renkinProject.renkin.ui.theme.InnerShape
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
    var pickerIndex by remember { mutableStateOf<Int?>(null) }
    var displayedAngle by remember(style.gradientAngle) {
        mutableFloatStateOf(normalizeGradientAngle(style.gradientAngle))
    }
    val gradientColors = remember(style.firstColor, style.gradientStops) {
        listOf(style.firstColor) + style.gradientStops
    }

    fun withColors(colors: List<Int>): ColorizerStyle =
        style.copy(firstColor = colors.first(), gradientStops = colors.drop(1))

    ColorizerModeToggle(
        style = style,
        onModeChange = { onStyleChange(style.copy(mode = it)) }
    )

    // One crossfading container instead of two independent AnimatedVisibility blocks: those
    // overlapped mid-switch and made the sheet jump.
    AnimatedContent(
        targetState = style.mode,
        transitionSpec = {
            (fadeIn(tween(180)) togetherWith fadeOut(tween(120)))
                .using(SizeTransform(clip = false))
        },
        label = "colorizerMode"
    ) { mode ->
        if (mode == ColorizerMode.SINGLE_COLOR) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorizerColorRow(
                    label = stringResource(R.string.iconColor),
                    color = Color(style.firstColor),
                    onClick = { pickerIndex = 0 }
                )
                if (showSingleColorEffects) {
                    // Gradient mode keeps these values and honours them, it just has no room to
                    // repeat the three switches under an already tall stop list — hence the
                    // heading, so nobody thinks they only affect a single colour.
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = stringResource(R.string.colorizeEffectsTitle),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Text(
                        text = stringResource(R.string.colorizeEffectsSubtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp)
                    )
                    ColorizerSwitchRow(
                        label = stringResource(R.string.colorizeSolid),
                        hint = stringResource(R.string.colorizeSolidHint),
                        checked = style.flat,
                        onCheckedChange = {
                            onStyleChange(
                                style.copy(
                                    flat = it,
                                    monochrome = if (it) false else style.monochrome
                                )
                            )
                        }
                    )
                    ColorizerSwitchRow(
                        label = stringResource(R.string.colorizeMonochrome),
                        hint = stringResource(R.string.colorizeMonochromeHint),
                        checked = style.monochrome,
                        onCheckedChange = {
                            onStyleChange(
                                style.copy(
                                    monochrome = it,
                                    flat = if (it) false else style.flat
                                )
                            )
                        }
                    )
                    ColorizerSwitchRow(
                        label = stringResource(R.string.inverseColors),
                        checked = style.inverse,
                        onCheckedChange = { onStyleChange(style.copy(inverse = it)) }
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GradientStopList(
                    style = style,
                    colors = gradientColors,
                    onColorsChange = { onStyleChange(withColors(it)) },
                    onEditColor = { pickerIndex = it }
                )
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
                if (showSingleColorEffects && (style.flat || style.monochrome || style.inverse)) {
                    Text(
                        text = stringResource(R.string.colorizeEffectsActive),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
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
    }

    pickerIndex?.let { index ->
        val gradient = style.mode == ColorizerMode.GRADIENT
        ColorDialog(
            onDismiss = { pickerIndex = null },
            currentlySelected = Color(
                gradientColors.getOrElse(index) { style.firstColor }
            ),
            onColorSelected = { selected ->
                val selectedArgb = selected.toArgb()
                onStyleChange(
                    if (gradient) {
                        withColors(gradientColors.toMutableList().also { it[index] = selectedArgb })
                    } else {
                        style.copy(firstColor = selectedArgb)
                    }
                )
            },
            sampleBitmap = sampleBitmap
        )
    }
}

/**
 * Single/Gradient switch. Material 3's SegmentedButton cannot animate its selection across the
 * row, so the pill is hand-built — but it uses the theme's own primary/surface roles, never the
 * colours being edited, so it matches every other control in the app.
 */
@Composable
private fun ColorizerModeToggle(
    style: ColorizerStyle,
    onModeChange: (ColorizerMode) -> Unit
) {
    val gradient = style.mode == ColorizerMode.GRADIENT

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(TogglePillHeight + TogglePillPadding * 2)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .selectableGroup()
    ) {
        val halfWidth = (maxWidth - TogglePillPadding * 2) / 2
        val pillOffset by animateDpAsState(
            targetValue = if (gradient) TogglePillPadding + halfWidth else TogglePillPadding,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
            label = "colorizerPill"
        )
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .offset(x = pillOffset, y = TogglePillPadding)
                .size(width = halfWidth, height = TogglePillHeight)
        ) {}
        Row(modifier = Modifier.fillMaxSize()) {
            ColorizerMode.entries.forEach { mode ->
                val selected = style.mode == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onModeChange(mode) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(
                            if (mode == ColorizerMode.SINGLE_COLOR) {
                                R.string.colorizerSingleColor
                            } else {
                                R.string.colorizerGradient
                            }
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

/**
 * Reorderable gradient stops. Dragging is bound to the grip handle only — a whole-row drag would
 * fight the scrolling container the editor lives in.
 */
@Composable
private fun GradientStopList(
    style: ColorizerStyle,
    colors: List<Int>,
    onColorsChange: (List<Int>) -> Unit,
    onEditColor: (Int) -> Unit
) {
    val rowStridePx = with(LocalDensity.current) { (StopRowHeight + 8.dp).toPx() }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val targetIndex = if (dragIndex < 0) {
        -1
    } else {
        (dragIndex + (dragOffset / rowStridePx).roundToInt()).coerceIn(0, colors.lastIndex)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(InnerShape)
                .colorizerSwatch(style)
        )

        colors.forEachIndexed { index, color ->
            // Neighbours slide out of the way so the drop position is visible before releasing.
            val shift = when {
                dragIndex < 0 -> 0f
                dragIndex < targetIndex && index in (dragIndex + 1)..targetIndex -> -rowStridePx
                dragIndex > targetIndex && index in targetIndex until dragIndex -> rowStridePx
                else -> 0f
            }
            val animatedShift by animateFloatAsState(
                targetValue = shift,
                label = "stopShift"
            )
            GradientStopRow(
                color = Color(color),
                dragged = index == dragIndex,
                canRemove = colors.size > MIN_GRADIENT_STOPS,
                canMoveUp = index > 0,
                canMoveDown = index < colors.lastIndex,
                onClick = { onEditColor(index) },
                onRemove = { onColorsChange(colors.filterIndexed { i, _ -> i != index }) },
                onMove = { step ->
                    val to = (index + step).coerceIn(0, colors.lastIndex)
                    onColorsChange(colors.toMutableList().apply { add(to, removeAt(index)) })
                },
                onDragStart = {
                    dragIndex = index
                    dragOffset = 0f
                },
                onDrag = { dragOffset += it },
                onDragStop = {
                    if (dragIndex >= 0 && targetIndex != dragIndex) {
                        onColorsChange(
                            colors.toMutableList().apply { add(targetIndex, removeAt(dragIndex)) }
                        )
                    }
                    dragIndex = -1
                    dragOffset = 0f
                },
                modifier = Modifier
                    .zIndex(if (index == dragIndex) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (index == dragIndex) dragOffset else animatedShift
                    }
            )
        }

        if (colors.size < MAX_GRADIENT_STOPS) {
            OutlinedButton(
                onClick = { onColorsChange(colors + colors.last()) },
                shape = FieldShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.gradientAddColor))
            }
        }
        Text(
            text = stringResource(R.string.gradientStopsHint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun GradientStopRow(
    color: Color,
    dragged: Boolean,
    canRemove: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val moveUpLabel = stringResource(R.string.gradientMoveUp)
    val moveDownLabel = stringResource(R.string.gradientMoveDown)
    val reorderLabel = stringResource(R.string.gradientReorderColor)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragStop by rememberUpdatedState(onDragStop)

    Surface(
        shape = InnerShape,
        color = if (dragged) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        tonalElevation = if (dragged) 6.dp else 0.dp,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(StopRowHeight)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.DragIndicator,
                contentDescription = reorderLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .semantics {
                        // Dragging is unreachable with a screen reader, so the same reordering
                        // is exposed as explicit actions.
                        customActions = buildList {
                            if (canMoveUp) {
                                add(CustomAccessibilityAction(moveUpLabel) { onMove(-1); true })
                            }
                            if (canMoveDown) {
                                add(CustomAccessibilityAction(moveDownLabel) { onMove(1); true })
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { currentOnDragStart() },
                            onDragEnd = { currentOnDragStop() },
                            onDragCancel = { currentOnDragStop() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                currentOnDrag(dragAmount.y)
                            }
                        )
                    }
            )
            Surface(
                shape = CircleShape,
                color = color,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.size(30.dp)
            ) {}
            Text(
                text = color.toHexString().uppercase(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.gradientRemoveColor)
                    )
                }
            }
        }
    }
}

private val StopRowHeight = 56.dp
private val TogglePillHeight = 44.dp
private val TogglePillPadding = 5.dp

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
