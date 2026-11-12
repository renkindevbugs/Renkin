package dev.renkinProject.renkin.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.theme.CardShape
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun Modifier.editorLoadingBorder(loading: Boolean): Modifier {
    var visible by remember { mutableStateOf(loading) }
    var finishing by remember { mutableStateOf(false) }
    var stoppedProgress by remember { mutableFloatStateOf(0f) }
    val completion = remember { Animatable(0f) }
    val opacity = remember { Animatable(1f) }
    if (!loading && !visible) return this
    val color = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.editorLoading)
    val transition = rememberInfiniteTransition(label = "editorLoadingBorder")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "editorLoadingPosition"
    )
    LaunchedEffect(loading) {
        if (loading) {
            visible = true
            finishing = false
            completion.snapTo(0f)
            opacity.snapTo(1f)
        } else {
            stoppedProgress = phase.value
            finishing = true
            completion.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
            opacity.animateTo(0f, tween(120, easing = LinearEasing))
            visible = false
        }
    }
    // Completion is visual only: accessibility and controls follow the real work state.
    val activity = if (loading) semantics {
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        stateDescription = description
    } else this
    return activity.drawWithCache {
        val width = 2.dp.toPx()
        val inset = width / 2f
        val outline = CardShape.createOutline(
            Size((size.width - width).coerceAtLeast(0f), (size.height - width).coerceAtLeast(0f)),
            layoutDirection,
            this
        )
        val perimeter = Path().apply {
            when (outline) {
                is Outline.Rounded -> addRoundRect(outline.roundRect, Path.Direction.Clockwise)
                is Outline.Rectangle -> addRect(outline.rect, Path.Direction.Clockwise)
                is Outline.Generic -> addPath(outline.path)
            }
            translate(Offset(inset, inset))
        }
        val measure = PathMeasure().apply { setPath(perimeter, forceClosed = true) }
        val segment = Path()
        val stroke = Stroke(width = width, cap = StrokeCap.Round)
        onDrawWithContent {
            drawContent()
            val length = measure.length
            if (length > 0f) {
                val progress = if (finishing) stoppedProgress else phase.value
                val start = (progress * length) % length
                val baseExtent = 0.18f + 0.10f * sin(progress * 2f * PI.toFloat())
                val fraction = if (finishing) baseExtent + (1f - baseExtent) * completion.value else baseExtent
                val extent = length * fraction
                val end = start + extent
                val alpha = opacity.value
                drawPath(perimeter, color.copy(alpha = 0.16f * alpha), style = stroke)
                if (fraction >= 1f) {
                    drawPath(perimeter, color.copy(alpha = alpha), style = stroke)
                } else {
                    segment.reset()
                    measure.getSegment(start, minOf(end, length), segment)
                    if (end > length) measure.getSegment(0f, end - length, segment)
                    drawPath(segment, color.copy(alpha = alpha), style = stroke)
                }
            }
        }
    }
}
