package dev.renkinProject.renkin.feature.help

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.theme.DialogShape
import kotlin.math.roundToInt

internal enum class IntroTarget {
    SOURCE, REFRESH, ADVANCED_OPTIONS, APP_ROW, WATCHED_ICONS, BUILD, PROFILE, SETTINGS, INFO
}

/**
 * Window bounds of every on-screen element the intro can point at, plus how to bring a target into
 * view when it sits in a scrolled list (the first app row is off-screen once Advanced options is open).
 */
@Stable
internal class IntroTargets {
    val bounds = mutableStateMapOf<IntroTarget, Rect>()
    val revealers = mutableMapOf<IntroTarget, suspend () -> Unit>()
}

// Null outside the main screen, which keeps [introTarget] free everywhere else.
internal val LocalIntroTargets = staticCompositionLocalOf<IntroTargets?> { null }

internal fun Modifier.introTarget(target: IntroTarget): Modifier = composed {
    val targets = LocalIntroTargets.current ?: return@composed Modifier
    // A scrolled-away or recycled element must not leave a stale window behind.
    DisposableEffect(targets, target) {
        onDispose { targets.bounds.remove(target) }
    }
    Modifier.onGloballyPositioned { targets.bounds[target] = it.boundsInWindow() }
}

private data class IntroStep(
    val targets: List<IntroTarget>,
    @get:StringRes val title: Int,
    @get:StringRes val text: Int
)

// The order a first pack actually happens in, then the places people come back to.
private val IntroSteps = listOf(
    IntroStep(listOf(IntroTarget.SOURCE), R.string.onboardingSourceTitle, R.string.onboardingSourceText),
    // Advanced options is lit with Refresh: its changes only reach the icons on the next refresh.
    IntroStep(
        listOf(IntroTarget.REFRESH, IntroTarget.ADVANCED_OPTIONS),
        R.string.onboardingRefreshTitle,
        R.string.onboardingRefreshText
    ),
    IntroStep(listOf(IntroTarget.APP_ROW), R.string.onboardingEditTitle, R.string.onboardingEditText),
    IntroStep(listOf(IntroTarget.WATCHED_ICONS), R.string.introWatchTitle, R.string.introWatchText),
    IntroStep(listOf(IntroTarget.BUILD), R.string.onboardingBuildTitle, R.string.onboardingBuildText),
    IntroStep(listOf(IntroTarget.PROFILE), R.string.introProfileTitle, R.string.introProfileText),
    IntroStep(listOf(IntroTarget.SETTINGS), R.string.introSettingsTitle, R.string.introSettingsText),
    IntroStep(listOf(IntroTarget.INFO), R.string.introInfoTitle, R.string.introInfoText)
)

private val HighlightPadding = 6.dp
private val HighlightCorner = 20.dp
private val CardGap = 12.dp
private val CardMargin = 16.dp
private val CardMaxWidth = 420.dp

/**
 * First-run intro drawn over the live main screen: dims everything except the element each step
 * explains. Taps on the dimmed screen are swallowed so the tour can't be left half-way by
 * accident; Skip and the back gesture on the first step end it via [onFinish].
 */
@Composable
internal fun IntroShowcase(targets: IntroTargets, onFinish: () -> Unit) {
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val step = IntroSteps[stepIndex]
    var revealing by remember { mutableStateOf(false) }
    BackHandler { if (stepIndex > 0) stepIndex-- else onFinish() }
    LaunchedEffect(step) {
        val reveal = step.targets.firstNotNullOfOrNull { targets.revealers[it] } ?: return@LaunchedEffect
        revealing = true
        try {
            reveal()
        } finally {
            revealing = false
        }
    }

    var origin by remember { mutableStateOf(Offset.Zero) }
    val current = step.targets.mapNotNull { targets.bounds[it]?.translate(-origin) }
    // While a target is still scrolling into view it has no bounds yet; keeping the previous
    // window lets the cutout glide to it instead of vanishing and popping back.
    val lastHighlights = remember { LastHighlights() }
    if (current.isNotEmpty()) lastHighlights.rects = current
    val highlights = current.ifEmpty { lastHighlights.rects }
    // Two slots cover every step; a one-target step folds the second window onto the first.
    val firstWindow = animatedWindow(highlights.firstOrNull())
    val secondWindow = animatedWindow(highlights.getOrNull(1) ?: highlights.firstOrNull())
    val cardAlpha by animateFloatAsState(if (revealing) 0f else 1f, label = "introCardAlpha")
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)
    val ring = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val systemBars = WindowInsets.systemBars

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInWindow().topLeft }
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            drawRect(scrim)
            val corner = CornerRadius(HighlightCorner.toPx())
            listOfNotNull(firstWindow, secondWindow).distinct().forEach { rect ->
                val window = rect.inflate(HighlightPadding.toPx())
                drawRoundRect(Color.Black, window.topLeft, window.size, corner, blendMode = BlendMode.Clear)
                drawRoundRect(ring, window.topLeft, window.size, corner, style = Stroke(2.dp.toPx()))
            }
        }

        var cardHeight by remember { mutableIntStateOf(0) }
        val cardTop = with(density) {
            val margin = CardMargin.roundToPx()
            val gap = (HighlightPadding + CardGap).roundToPx()
            introCardTop(
                blocked = highlights.map { (it.top.roundToInt() - gap)..(it.bottom.roundToInt() + gap) },
                cardHeight = cardHeight,
                safeTop = systemBars.getTop(this) + margin,
                safeBottom = constraints.maxHeight - systemBars.getBottom(this) - margin
            )
        }
        val animatedCardTop by animateIntAsState(cardTop, label = "introCardTop")
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, animatedCardTop) }
                .padding(horizontal = CardMargin)
                .widthIn(max = CardMaxWidth)
                .onSizeChanged { cardHeight = it.height }
                .graphicsLayer { alpha = cardAlpha }
        ) {
            IntroCard(
                stepNumber = stepIndex + 1,
                step = step,
                isFirst = stepIndex == 0,
                isLast = stepIndex == IntroSteps.lastIndex,
                onBack = { stepIndex-- },
                onNext = { if (stepIndex == IntroSteps.lastIndex) onFinish() else stepIndex++ },
                onSkip = onFinish
            )
        }
    }
}

private class LastHighlights {
    var rects: List<Rect> = emptyList()
}

// Glides between targets; the very first target appears in place instead of growing from a corner.
@Composable
private fun animatedWindow(target: Rect?): Rect? {
    var window by remember { mutableStateOf<Animatable<Rect, AnimationVector4D>?>(null) }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        val current = window
        if (current == null) window = Animatable(target, Rect.VectorConverter) else current.animateTo(target)
    }
    return window?.value
}

/**
 * Where the card goes so it never covers a highlight: the free vertical gaps between the [blocked]
 * ranges (highlights plus their gap) are tried largest first, and the card hugs the highlight that
 * borders the gap. Nothing highlighted centres it; no gap big enough pins it to the bottom.
 */
internal fun introCardTop(
    blocked: List<IntRange>,
    cardHeight: Int,
    safeTop: Int,
    safeBottom: Int
): Int {
    if (blocked.isEmpty()) return safeTop + (safeBottom - safeTop - cardHeight) / 2
    val gaps = buildList {
        var start = safeTop
        blocked.sortedBy { it.first }.forEach { range ->
            if (range.first > start) add(start..range.first)
            start = maxOf(start, range.last)
        }
        if (safeBottom > start) add(start..safeBottom)
    }
    val gap = gaps
        .filter { it.last - it.first >= cardHeight }
        .maxByOrNull { it.last - it.first }
        ?: return (safeBottom - cardHeight).coerceAtLeast(safeTop)
    val bordersHighlightAbove = gap.first != safeTop
    return if (bordersHighlightAbove) gap.first else gap.last - cardHeight
}

@Composable
private fun IntroCard(
    stepNumber: Int,
    step: IntroStep,
    isFirst: Boolean,
    isLast: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    Surface(
        shape = DialogShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.introStepCounter, stepNumber, IntroSteps.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(step.title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = stringResource(step.text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!isLast) {
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.onboardingSkip)) }
                }
                Spacer(Modifier.weight(1f))
                if (!isFirst) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.onboardingBack)) }
                }
                Button(onClick = onNext) {
                    Text(stringResource(if (isLast) R.string.done else R.string.onboardingNext))
                }
            }
        }
    }
}
