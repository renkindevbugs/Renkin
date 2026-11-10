package dev.renkinProject.renkin.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
    if (!loading) return this
    val color = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.editorLoading)
    val transition = rememberInfiniteTransition(label = "editorLoadingBorder")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "editorLoadingPosition"
    )
    return semantics {
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        stateDescription = description
    }.drawWithCache {
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
                val progress = phase.value
                val start = (progress * length) % length
                val extent = length * (0.18f + 0.10f * sin(progress * 2f * PI.toFloat()))
                val end = start + extent
                segment.reset()
                measure.getSegment(start, minOf(end, length), segment)
                if (end > length) measure.getSegment(0f, end - length, segment)
                drawPath(perimeter, color.copy(alpha = 0.16f), style = stroke)
                drawPath(segment, color, style = stroke)
            }
        }
    }
}
