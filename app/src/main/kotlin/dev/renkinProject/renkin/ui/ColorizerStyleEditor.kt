@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.drawBehind
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
import dev.renkinProject.renkin.icon.creator.clampedGradientPositions
import dev.renkinProject.renkin.icon.creator.evenGradientPositions
import dev.renkinProject.renkin.icon.creator.gradientColorAt
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
    // Which stop the bar highlights and the picker edits. Kept here so tapping a row, a handle
    // or a swatch all mean the same selection.
    var selectedStop by remember { mutableIntStateOf(0) }
    var displayedAngle by remember(style.gradientAngle) {
        mutableFloatStateOf(normalizeGradientAngle(style.gradientAngle))
    }
    val gradientColors = remember(style.firstColor, style.gradientStops) {
        listOf(style.firstColor) + style.gradientStops
    }
    // Gradients made before stop positions existed carry none; the even spread they already
    // paint becomes the starting point the handles drag from.
    val gradientPositions = remember(gradientColors, style.gradientPositions) {
        style.gradientPositions.takeIf { it.size == gradientColors.size }
            ?: evenGradientPositions(gradientColors.size)
    }

    /**
     * Commits [colors] at [positions] as given. The list order is never rearranged: a stop
     * dragged past its neighbour is clamped when painted, so handles stay under the finger that
     * moved them and rows keep their place.
     */
    fun applyStops(colors: List<Int>, positions: List<Float>) {
        onStyleChange(
            style.copy(
                firstColor = colors.first(),
                gradientStops = colors.drop(1),
                gradientPositions = positions
            )
        )
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
                    colors = gradientColors,
                    positions = gradientPositions,
                    selectedStop = selectedStop.coerceIn(0, gradientColors.lastIndex),
                    onSelect = { selectedStop = it },
                    onStopsChange = { colors, positions, select ->
                        applyStops(colors, positions)
                        selectedStop = select.coerceIn(0, colors.lastIndex)
                    },
                    onEditColor = {
                        selectedStop = it
                        pickerIndex = it
                    }
                )
                if (showSingleColorEffects && (style.flat || style.monochrome || style.inverse)) {
                    Text(
                        text = stringResource(R.string.colorizeEffectsActive),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                GradientShapeControl(
                    type = style.gradientType,
                    angle = displayedAngle,
                    onTypeChange = { onStyleChange(style.copy(gradientType = it)) },
                    onAngleChange = {
                        displayedAngle = it
                        onStyleChange(style.copy(gradientAngle = it))
                    }
                )
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
 * The stop bar plus one row per stop. The bar always paints left-to-right: rotating it with the
 * angle would make the handles chase the colours they set, and no gradient tool does that — the
 * angle belongs to the preview.
 */
@Composable
private fun GradientStopList(
    colors: List<Int>,
    positions: List<Float>,
    selectedStop: Int,
    onSelect: (Int) -> Unit,
    onStopsChange: (List<Int>, List<Float>, Int) -> Unit,
    onEditColor: (Int) -> Unit
) {
    val full = colors.size >= MAX_GRADIENT_STOPS

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GradientStopTrack(
            colors = colors,
            positions = positions,
            selectedStop = selectedStop,
            onSelect = onSelect,
            onMove = { index, position ->
                onStopsChange(colors, positions.toMutableList().also { it[index] = position }, index)
            },
            onAdd = { position ->
                if (!full) {
                    // The new stop takes the colour the gradient already paints there — halfway
                    // between black and white it is grey — so dropping a handle leaves the
                    // gradient looking identical until its colour is picked. It is inserted where
                    // it belongs: appending it would only clamp it against a later neighbour.
                    val at = positions.indexOfFirst { it > position }
                        .takeIf { it >= 0 } ?: colors.size
                    onStopsChange(
                        colors.toMutableList().apply {
                            add(at, gradientColorAt(colors, positions, position))
                        },
                        positions.toMutableList().apply { add(at, position) },
                        at
                    )
                }
            }
        )

        colors.forEachIndexed { index, color ->
            GradientStopRow(
                color = Color(color),
                position = positions.getOrElse(index) { 0f },
                selected = index == selectedStop,
                canRemove = colors.size > MIN_GRADIENT_STOPS,
                onClick = { onSelect(index) },
                onEditColor = { onEditColor(index) },
                onRemove = {
                    onStopsChange(
                        colors.filterIndexed { i, _ -> i != index },
                        positions.filterIndexed { i, _ -> i != index },
                        0
                    )
                },
                onNudge = { step ->
                    val moved = (positions[index] + step).coerceIn(0f, 1f)
                    onStopsChange(
                        colors,
                        positions.toMutableList().also { it[index] = moved },
                        index
                    )
                }
            )
        }

        if (!full) {
            OutlinedButton(
                onClick = {
                    // Appending at the end is the predictable spot for a button press; the bar
                    // is there for choosing where.
                    onStopsChange(colors + colors.last(), positions + 1f, colors.size)
                },
                shape = FieldShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.gradientAddColor))
            }
        }
        Text(
            text = if (full) {
                stringResource(R.string.gradientStopsFullHint, MAX_GRADIENT_STOPS)
            } else {
                stringResource(R.string.gradientStopsHint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

/**
 * Draggable stop handles over the gradient they describe. One pointer handler owns the whole bar
 * instead of one per handle: it decides on the down event whether the touch grabs the nearest
 * handle or drops a new stop, which is what makes handles a fingertip apart still separable.
 */
@Composable
private fun GradientStopTrack(
    colors: List<Int>,
    positions: List<Float>,
    selectedStop: Int,
    onSelect: (Int) -> Unit,
    onMove: (Int, Float) -> Unit,
    onAdd: (Float) -> Unit
) {
    val handleLabel = stringResource(R.string.gradientStopHandle)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnAdd by rememberUpdatedState(onAdd)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentStops by rememberUpdatedState(positions)
    val grabRadiusPx = with(LocalDensity.current) { StopGrabRadius.toPx() }
    val handleWidth = StopHandleWidth

    // The bar is inset by half a handle so a stop at 0 % or 100 % still sits fully inside it, and
    // by the ring so the handle travel matches the colours underneath it exactly.
    val edgeInset = handleWidth / 2 + TrackRing + TrackRingGap
    val translucent = colors.any { (it ushr 24) != 0xFF }
    val checkerLight = MaterialTheme.colorScheme.surfaceBright
    val checkerDark = MaterialTheme.colorScheme.surfaceDim
    val painted = remember(colors, positions) {
        val clamped = clampedGradientPositions(positions)
        colors.mapIndexed { index, color ->
            clamped.getOrElse(index) { 0f } to Color(color)
        }.toTypedArray()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(HandleHeight + HandleHalo * 2)
    ) {
        val travel = maxWidth - edgeInset * 2
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(TrackHeight)
                .padding(horizontal = handleWidth / 2)
                // Ring and gap in one chain: the outline reads against any colour, and the gap
                // keeps it from touching the gradient it is describing.
                .border(TrackRing, MaterialTheme.colorScheme.onSurface, InnerShape)
                .padding(TrackRing + TrackRingGap)
                .clip(InnerShape)
                // Translucent stops need something behind them, or "transparent" reads as
                // whatever the sheet's surface happens to be.
                .drawBehind {
                    if (translucent) drawAlphaCheckerboard(checkerLight, checkerDark)
                }
                .background(Brush.horizontalGradient(colorStops = painted))
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Same capture trick as the angle dial: consuming at the Main pass keeps the
                // surrounding vertical scroll from stealing a drag that starts on a handle.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Main
                        )
                        down.consume()
                        val insetPx = edgeInset.toPx()
                        val usable = (size.width - insetPx * 2f).coerceAtLeast(1f)
                        fun centerOf(index: Int) = currentStops[index] * usable + insetPx
                        fun fractionAt(x: Float) = ((x - insetPx) / usable).coerceIn(0f, 1f)

                        // Nearest wins, so two handles a fingertip apart are still separable and
                        // the top one is not permanently unreachable.
                        val touched = currentStops.indices.minByOrNull {
                            kotlin.math.abs(centerOf(it) - down.position.x)
                        }
                        val onHandle = touched != null &&
                            kotlin.math.abs(centerOf(touched) - down.position.x) <= grabRadiusPx
                        if (!onHandle || touched == null) {
                            currentOnAdd(fractionAt(down.position.x))
                            return@awaitEachGesture
                        }
                        currentOnSelect(touched)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.first()
                            if (change.pressed) {
                                currentOnMove(touched, fractionAt(change.position.x))
                            }
                            change.consume()
                        } while (change.pressed)
                    }
                }
        ) {
            colors.forEachIndexed { index, color ->
                val selected = index == selectedStop
                val position = positions.getOrElse(index) { 0f }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        // Half a handle taller than the bar, so the grabbable part is obvious and
                        // a stop never disappears into the colours it sets.
                        .offset(x = edgeInset - handleWidth / 2 - HandleHalo + travel * position)
                        .size(
                            width = handleWidth + HandleHalo * 2,
                            height = HandleHeight + HandleHalo * 2
                        )
                        .zIndex(if (selected) 1f else 0f)
                        .semantics {
                            contentDescription = handleLabel.format(index + 1)
                            progressBarRangeInfo = ProgressBarRangeInfo(position, 0f..1f)
                            setProgress { value ->
                                currentOnMove(index, value.coerceIn(0f, 1f))
                                true
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(
                                width = handleWidth + if (selected) HandleHalo * 2 else 0.dp,
                                height = HandleHeight + if (selected) HandleHalo * 2 else 0.dp
                            )
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        width = HandleHalo,
                                        // A translucent halo, not a colour swap: the selected
                                        // handle still has to show its own colour honestly.
                                        color = MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = 0.25f
                                        ),
                                        shape = CircleShape
                                    )
                                } else Modifier
                            )
                            .padding(if (selected) HandleHalo else 0.dp)
                            .border(HandleBorder, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .padding(HandleBorder)
                            .border(HandleBorder, MaterialTheme.colorScheme.surface, CircleShape)
                            .padding(HandleBorder)
                            .clip(CircleShape)
                            .drawBehind {
                                if ((color ushr 24) != 0xFF) {
                                    drawAlphaCheckerboard(checkerLight, checkerDark)
                                }
                            }
                            .background(Color(color))
                    )
                }
            }
        }
    }
}

@Composable
private fun GradientStopRow(
    color: Color,
    position: Float,
    selected: Boolean,
    canRemove: Boolean,
    onClick: () -> Unit,
    onEditColor: () -> Unit,
    onRemove: () -> Unit,
    onNudge: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val moveStartLabel = stringResource(R.string.gradientStopMoveStart)
    val moveEndLabel = stringResource(R.string.gradientStopMoveEnd)
    val editColorLabel = stringResource(R.string.gradientEditColor)

    Surface(
        shape = InnerShape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(StopRowHeight)
            .semantics {
                // Dragging a handle is unreachable with a screen reader, so the same move is
                // offered in steps.
                customActions = listOf(
                    CustomAccessibilityAction(moveStartLabel) { onNudge(-StopNudge); true },
                    CustomAccessibilityAction(moveEndLabel) { onNudge(StopNudge); true }
                )
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Only the swatch opens the picker: tapping the row is how a stop is selected on the
            // bar, and one gesture cannot mean both.
            val checkerLight = MaterialTheme.colorScheme.surfaceBright
            val checkerDark = MaterialTheme.colorScheme.surfaceDim
            Surface(
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                onClick = onEditColor,
                modifier = Modifier
                    .size(32.dp)
                    .semantics { contentDescription = editColorLabel }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            if (color.alpha < 1f) drawAlphaCheckerboard(checkerLight, checkerDark)
                            drawRect(color)
                        }
                )
            }
            Text(
                text = color.toHexString().uppercase(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${(position * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
// Bar, ring and handle proportions follow the web gradient editors: a short bar with a clear
// outline, and handles tall enough to grab without covering the colours they set.
private val TrackHeight = 30.dp
private val TrackRing = 2.dp
private val TrackRingGap = 2.dp
private val StopHandleWidth = 20.dp
private val HandleHeight = 46.dp
private val HandleBorder = 2.dp
// The soft ring that marks the selected handle, and the slack the row keeps for it.
private val HandleHalo = 4.dp
// How close a touch has to land to count as grabbing a handle rather than dropping a new stop.
private val StopGrabRadius = 22.dp
/** Screen-reader step for moving a stop, in track fractions. */
private const val StopNudge = 0.05f
// Smaller than the old standalone dial: it now shares its row with the gradient type choice.
private val DialSize = 108.dp

@Composable
private fun ColorizerColorRow(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val checkerLight = MaterialTheme.colorScheme.surfaceBright
    val checkerDark = MaterialTheme.colorScheme.surfaceDim

    OptionCard(
        label = label,
        onClick = onClick,
        modifier = modifier,
        trailing = {
            Surface(
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.size(28.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            if (color.alpha < 1f) drawAlphaCheckerboard(checkerLight, checkerDark)
                            drawRect(color)
                        }
                )
            }
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


/**
 * Gradient shape and direction on one line: the type choice takes the space the angle presets
 * used to, and the dial does the rest — it already magnets onto the 45° steps those chips offered.
 */
@Composable
private fun GradientShapeControl(
    type: GradientType,
    angle: Float,
    onTypeChange: (GradientType) -> Unit,
    onAngleChange: (Float) -> Unit
) {
    val angleLabel = stringResource(R.string.gradientAngle)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GradientType.entries.forEach { entry ->
                val selected = type == entry
                Surface(
                    shape = FieldShape,
                    color = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onTypeChange(entry) }
                        )
                ) {
                    Text(
                        text = stringResource(
                            if (entry == GradientType.LINEAR) {
                                R.string.gradientLinear
                            } else {
                                R.string.gradientRadial
                            }
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
        // A radial gradient has no direction, but hiding the dial would collapse the row and
        // make every switch jump; it fades out and stops taking input instead.
        val enabled = type == GradientType.LINEAR
        val dialAlpha by animateFloatAsState(if (enabled) 1f else 0.38f, label = "dialAlpha")
        AngleDial(
            angle = angle,
            contentDescription = angleLabel,
            enabled = enabled,
            onAngleChange = onAngleChange,
            modifier = Modifier.graphicsLayer { alpha = dialAlpha }
        )
    }
}

/** Shared with the gradient gallery, where the same dial previews a preset's direction. */
@Composable
internal fun AngleDial(
    angle: Float,
    contentDescription: String,
    onAngleChange: (Float) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
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

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(DialSize)
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
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
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
