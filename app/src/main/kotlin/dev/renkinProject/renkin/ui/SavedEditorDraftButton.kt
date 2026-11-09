package dev.renkinProject.renkin.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.theme.readableAddedTextColor
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun SavedEditorDraftButton(reduceMotion: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.editorAutosaveReview)
    val ringColor = readableAddedTextColor(MaterialTheme.colorScheme.background)
    val pulse = if (reduceMotion) null else rememberInfiniteTransition(label = "savedEditAttention").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "savedEditPulse"
    )
    RenkinTooltipBox(label) {
        Box(Modifier.size(48.dp).drawBehind {
            val progress = pulse?.value ?: return@drawBehind
            // The second half of each cycle rests, leaving only the unchanged icon.
            if (progress < 0.5f) {
                val expansion = progress * 2f
                drawCircle(
                    color = ringColor,
                    alpha = sin(PI * expansion).toFloat() * 0.6f,
                    radius = (10f + 13f * FastOutSlowInEasing.transform(expansion)).dp.toPx(),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }) {
            IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Filled.Restore,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
