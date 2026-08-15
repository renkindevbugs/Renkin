package dev.renkinProject.renkin.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.theme.DialogShape
import dev.renkinProject.renkin.ui.theme.InnerShape
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How a slider's value reads on the ruler: the smallest move a tick represents, how often a tick
 * is drawn tall and numbered, and how a value becomes the text under it. The optional mapping
 * keeps the picker reusable for sliders whose visual coordinate is not their displayed value.
 */
data class RulerSpec(
    val step: Float,
    val majorEvery: Int = 10,
    val valueRange: ClosedFloatingPointRange<Float>? = null,
    val toRulerValue: (Float) -> Float = { it },
    val fromRulerValue: (Float) -> Float = { it },
    val format: (Float) -> String
) {
    init {
        require(step.isFinite() && step > 0f) { "Ruler step must be positive and finite" }
        require(majorEvery > 0) { "Major tick interval must be positive" }
    }
}

/**
 * Numeric picker for a slider's value: a ruler that scrolls under a fixed centre mark. A slider
 * spreads a hundred steps over a phone's width — three pixels each, which is what makes hitting
 * an exact number a game. Here one step is a comfortable drag, and the two buttons cover the
 * final unit without a keyboard.
 *
 * Live: [onValueChange] fires while the ruler moves, so whatever the slider drives (an icon
 * preview) follows along instead of jumping when the dialog closes.
 */
@Composable
fun RulerPickerDialog(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    spec: RulerSpec,
    onValueChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    // The ruler works in whole steps; the caller's float is only translated at the edges.
    val steps = remember(valueRange, spec.step) {
        ((valueRange.endInclusive - valueRange.start) / spec.step).roundToInt()
    }
    fun stepOf(raw: Float): Int =
        ((raw - valueRange.start) / spec.step).roundToInt().coerceIn(0, steps)

    fun valueAt(step: Float): Float =
        (valueRange.start + step * spec.step).coerceIn(valueRange.start, valueRange.endInclusive)

    // Drag updates must be synchronous. Launching Animatable.snapTo for every pointer pixel can
    // reorder/cancel updates under a fast gesture and make the ruler lag behind the finger.
    val initialStep = remember { stepOf(value) }
    var position by remember { mutableFloatStateOf(initialStep.toFloat()) }
    var lastReported by remember { mutableFloatStateOf(valueAt(initialStep.toFloat())) }
    var motionJob by remember { mutableStateOf<Job?>(null) }
    // The step the newest button press aims at, so held repeats chain instead of fighting the
    // settle animation. Cleared once the ruler has actually arrived.
    var pendingTarget by remember { mutableStateOf<Int?>(null) }
    val stepPx = with(density) { RULER_STEP.toPx() }
    val minFlingVelocityPx = with(density) { MIN_FLING_VELOCITY.toPx() }

    // Report only whole steps: a live preview regenerating on every fractional pixel would keep
    // the pipeline busy with values the user never stops on. snapshotFlow rather than an effect
    // keyed on the position — that would tear down and restart a coroutine every animation frame.
    LaunchedEffect(Unit) {
        // A slider can hold a fractional value while its badge already displays a rounded one.
        // Opening the exact picker makes those two agree without vibrating on initial display.
        if (abs(lastReported - value) > VALUE_EPSILON) {
            currentOnValueChange(lastReported)
        }
        snapshotFlow { position.roundToInt() }
            .distinctUntilChanged()
            .collect { step ->
                val settled = valueAt(step.toFloat())
                if (abs(settled - lastReported) <= VALUE_EPSILON) return@collect
                lastReported = settled
                currentOnValueChange(settled)
                view.performTickHaptic()
            }
    }

    suspend fun settleAt(target: Float) {
        animate(
            initialValue = position,
            targetValue = target.coerceIn(0f, steps.toFloat()),
            animationSpec = tween(RULER_SETTLE_MS)
        ) { animated, _ ->
            position = animated
        }
    }

    /**
     * A flick carries the ruler a few ticks further and stops. Deliberately NOT a scroll decay:
     * this picker exists for exact numbers and the slider above it is the coarse control, so a
     * throw that spins through the whole range would be the wrong tool. The travel is derived
     * from the release velocity, capped at [MAX_FLING_STEPS], and lands on a tick directly.
     */
    fun flingAndSnap(velocityPxPerSecond: Float) {
        motionJob?.cancel()
        pendingTarget = null
        val target = rulerFlingTarget(
            position = position,
            steps = steps,
            velocityPxPerSecond = velocityPxPerSecond,
            stepPx = stepPx,
            minFlingVelocityPx = minFlingVelocityPx
        )
        motionJob = scope.launch { settleAt(target.toFloat()) }
    }

    /**
     * One step per call, counted from the step the last call aimed at rather than from where the
     * ruler currently is. Held down, the repeats come faster than a settle animation finishes, and
     * reading the live position would keep re-targeting the tick it had not reached yet.
     */
    fun nudge(by: Int) {
        val from = pendingTarget ?: position.roundToInt()
        val target = (from + by).coerceIn(0, steps)
        pendingTarget = target
        motionJob?.cancel()
        motionJob = scope.launch {
            settleAt(target.toFloat())
            pendingTarget = null
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties()) {
        // A true shared-element transition cannot cross the dialog's window. This entrance echoes
        // the tapped badge by growing the surface and its number from a compact starting scale.
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        val entrance by animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = tween(RULER_ENTER_MS),
            label = "rulerEntrance"
        )
        Surface(
            shape = DialogShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val scale = BADGE_SCALE + (1f - BADGE_SCALE) * entrance
                    scaleX = scale
                    scaleY = scale
                    alpha = entrance
                }
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = stringResource(R.string.done),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Text(
                    text = spec.format(valueAt(position.roundToInt().toFloat())),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .graphicsLayer {
                            // Same origin as the surface: the digits swell from badge size.
                            val textScale = BADGE_TEXT_SCALE +
                                (1f - BADGE_TEXT_SCALE) * entrance
                            scaleX = textScale
                            scaleY = textScale
                        }
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledIconButton(
                        onClick = { nudge(-1) },
                        enabled = position.roundToInt() > 0,
                        modifier = Modifier
                            .size(40.dp)
                            .repeatingPress(
                                enabled = position.roundToInt() > 0,
                                onRepeat = { nudge(-1) }
                            )
                    ) {
                        Icon(Icons.Filled.Remove, stringResource(R.string.rulerDecrease))
                    }
                    RulerTrack(
                        position = position,
                        steps = steps,
                        spec = spec,
                        valueAt = ::valueAt,
                        modifier = Modifier
                            .weight(1f)
                            .height(84.dp)
                            .clip(InnerShape)
                            .draggable(
                                orientation = Orientation.Horizontal,
                                // A new touch must catch an in-flight ruler immediately, just as
                                // putting a finger on a scrolling list stops its fling.
                                startDragImmediately = true,
                                onDragStarted = { _ -> motionJob?.cancel() },
                                state = rememberDraggableState { delta ->
                                    motionJob?.cancel()
                                    pendingTarget = null
                                    position = (position - delta / stepPx)
                                        .coerceIn(0f, steps.toFloat())
                                },
                                onDragStopped = { velocity ->
                                    // Always land ON a tick: a ruler resting between two numbers
                                    // would leave the value ambiguous.
                                    flingAndSnap(velocity)
                                }
                            )
                    )
                    FilledIconButton(
                        onClick = { nudge(1) },
                        enabled = position.roundToInt() < steps,
                        modifier = Modifier
                            .size(40.dp)
                            .repeatingPress(
                                enabled = position.roundToInt() < steps,
                                onRepeat = { nudge(1) }
                            )
                    ) {
                        Icon(Icons.Filled.Add, stringResource(R.string.rulerIncrease))
                    }
                }
            }
        }
    }
}

/**
 * Holding a step button keeps stepping, faster the longer it is held — the behaviour every
 * numeric stepper has. The button's own onClick still handles the single tap (and keeps its
 * ripple and accessibility), so this only adds what happens AFTER [REPEAT_DELAY_MS].
 */
private fun Modifier.repeatingPress(
    enabled: Boolean,
    onRepeat: () -> Unit
): Modifier = composed {
    val currentOnRepeat by rememberUpdatedState(onRepeat)
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        coroutineScope {
            val gestures = this
            awaitEachGesture {
                // Not requireUnconsumed: the button's clickable has already seen this down, and
                // waiting for an unconsumed one would never fire.
                awaitFirstDown(requireUnconsumed = false)
                val repeats = gestures.launch {
                    delay(REPEAT_DELAY_MS)
                    var interval = REPEAT_START_INTERVAL_MS
                    while (isActive) {
                        currentOnRepeat()
                        delay(interval)
                        interval = (interval * REPEAT_SPEED_UP)
                            .toLong()
                            .coerceAtLeast(REPEAT_MIN_INTERVAL_MS)
                    }
                }
                // Any end of the gesture stops it: lifting, or the touch being taken over.
                waitForUpOrCancellation()
                repeats.cancel()
            }
        }
    }
}

/** The ruler itself: ticks around [position], numbered on every [RulerSpec.majorEvery]th. */
@Composable
private fun RulerTrack(
    position: Float,
    steps: Int,
    spec: RulerSpec,
    valueAt: (Float) -> Float,
    modifier: Modifier = Modifier
) {
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    val majorColor = MaterialTheme.colorScheme.onSurface
    val markColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val background = MaterialTheme.colorScheme.surfaceContainerLowest
    val density = LocalDensity.current
    val labelSizePx = with(density) { 11.sp.toPx() }
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    Canvas(modifier) {
        drawRect(background)
        val stepPx = RULER_STEP.toPx()
        val centre = size.width / 2f
        // Only the ticks that can land inside the track are worth walking.
        val visible = (size.width / stepPx / 2f).toInt() + 2
        val first = (position.roundToInt() - visible).coerceAtLeast(0)
        val last = (position.roundToInt() + visible).coerceAtMost(steps)

        for (step in first..last) {
            val x = centre + (step - position) * stepPx
            // Major labels describe the actual value, not the offset from the range's start.
            // A 1..16 px ruler should read 5, 10, 15 rather than 1, 6, 11, 16.
            val absoluteStep = (valueAt(step.toFloat()) / spec.step).roundToInt()
            val major = absoluteStep % spec.majorEvery == 0
            val mid = absoluteStep % (spec.majorEvery / 2).coerceAtLeast(1) == 0
            val tickHeight = when {
                major -> size.height * 0.42f
                mid -> size.height * 0.30f
                else -> size.height * 0.20f
            }
            drawLine(
                color = if (major) majorColor else tickColor,
                start = Offset(x, size.height * 0.16f),
                end = Offset(x, size.height * 0.16f + tickHeight),
                strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
                cap = StrokeCap.Round
            )
            if (major) {
                drawRulerLabel(
                    text = spec.format(valueAt(step.toFloat())),
                    x = x,
                    y = size.height * 0.16f + tickHeight + labelSizePx + 6.dp.toPx(),
                    sizePx = labelSizePx,
                    color = labelColor.toArgb(),
                    paint = labelPaint
                )
            }
        }

        // The mark the ruler reads against, drawn last so no tick sits on top of it.
        drawLine(
            color = markColor,
            start = Offset(centre, size.height * 0.10f),
            end = Offset(centre, size.height * 0.72f),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

/** Compose has no text drawing in a DrawScope; the platform canvas does it without a layout. */
private fun DrawScope.drawRulerLabel(
    text: String,
    x: Float,
    y: Float,
    sizePx: Float,
    color: Int,
    paint: android.graphics.Paint
) {
    paint.textSize = sizePx
    paint.color = color
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

// One step is a comfortable drag rather than the ~3 dp a full-width slider gives it.
private val RULER_STEP = 10.dp
private val MIN_FLING_VELOCITY = 80.dp
// How much of one second's velocity a flick actually carries, and the ceiling on that carry.
// Both are small on purpose: the ruler nudges a few ticks and stops, it does not coast.
private const val FLING_STEPS_PER_SECOND = 0.12f
private const val MAX_FLING_STEPS = 6f
private const val RULER_SETTLE_MS = 180
// Hold-to-repeat: a beat before it starts, then steps that speed up to a comfortable ceiling.
private const val REPEAT_DELAY_MS = 380L
private const val REPEAT_START_INTERVAL_MS = 130L
private const val REPEAT_MIN_INTERVAL_MS = 35L
private const val REPEAT_SPEED_UP = 0.82f
private const val RULER_ENTER_MS = 220
// Where the dialog starts its entrance: roughly the size of the badge it grew from.
private const val BADGE_SCALE = 0.85f
private const val BADGE_TEXT_SCALE = 0.4f
private const val VALUE_EPSILON = 0.00001f

/** A 0..1 float read as a percentage — icon scale, plate scale, tolerances. */
fun percentRuler(
    suffix: String = "%",
    valueRange: ClosedFloatingPointRange<Float>? = null,
    toRulerValue: (Float) -> Float = { it },
    fromRulerValue: (Float) -> Float = { it }
): RulerSpec = RulerSpec(
    step = 0.01f,
    majorEvery = 10,
    valueRange = valueRange,
    toRulerValue = toRulerValue,
    fromRulerValue = fromRulerValue
) { "${(it * 100).roundToInt()}$suffix" }

/** A whole-pixel value, e.g. the outline's thickness. */
fun pixelRuler(): RulerSpec =
    RulerSpec(step = 1f, majorEvery = 5) { "${it.roundToInt()} px" }

/** A plain count, e.g. how many segments the artwork is split into. */
fun countRuler(majorEvery: Int = 5): RulerSpec =
    RulerSpec(step = 1f, majorEvery = majorEvery) { "${it.roundToInt()}" }

/** A value read in tenths, e.g. a blur radius where whole units are too coarse. */
fun decimalRuler(majorEvery: Int = 5): RulerSpec =
    RulerSpec(step = 0.1f, majorEvery = majorEvery) { "%.1f".format(it) }

/**
 * Where a flick lands: the release velocity carries the ruler a few ticks further, capped at
 * [MAX_FLING_STEPS] and clamped to the ruler's own ends. Below [MIN_FLING_VELOCITY] a release is
 * not a flick at all and only settles on the nearest tick.
 *
 * Dragging right moves the ruler left under the centre mark, so a rightward flick (positive
 * velocity) continues towards LOWER steps — the same sign the drag handler uses.
 */
internal fun rulerFlingTarget(
    position: Float,
    steps: Int,
    velocityPxPerSecond: Float,
    stepPx: Float,
    minFlingVelocityPx: Float
): Int {
    val carried = if (abs(velocityPxPerSecond) >= minFlingVelocityPx) {
        (velocityPxPerSecond / stepPx * FLING_STEPS_PER_SECOND)
            .coerceIn(-MAX_FLING_STEPS, MAX_FLING_STEPS)
    } else {
        0f
    }
    return (position - carried).roundToInt().coerceIn(0, steps)
}
