@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package dev.alembiconsProject.alembicons.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.extension.contentBounds
import dev.alembiconsProject.alembicons.ui.theme.CardShape
import kotlin.math.roundToInt

/**
 * Visual positioning tool (opened from the Modifier tab's Adjustments). Shows the current icon with
 * its content bounding box and dashed guides to each edge, so the user can see how far the artwork
 * sits from the top / bottom / left / right. Auto-centre snaps it to the middle; the sliders nudge it
 * manually. All three feed the same modifier pipeline as the other adjustments.
 */
@Composable
internal fun CenterDialog(
    iconBitmap: Bitmap?,
    autoCenter: Boolean,
    offsetX: Float,
    offsetY: Float,
    onAutoCenterChange: (Boolean) -> Unit,
    onOffsetXChange: (Float) -> Unit,
    onOffsetYChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val bounds = remember(iconBitmap) { iconBitmap?.contentBounds() }
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline

    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.position)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.layout.Box(
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
                        if (bounds != null) {
                            Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                                val bw = iconBitmap.width.toFloat()
                                val bh = iconBitmap.height.toFloat()
                                val l = bounds.left / bw * size.width
                                val t = bounds.top / bh * size.height
                                val r = bounds.right / bw * size.width
                                val b = bounds.bottom / bh * size.height
                                val cx = (l + r) / 2f
                                val cy = (t + b) / 2f
                                drawRect(primary, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
                                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                                val px = 1.dp.toPx()
                                drawLine(outline, Offset(cx, t), Offset(cx, 0f), px, pathEffect = dash)
                                drawLine(outline, Offset(cx, b), Offset(cx, size.height), px, pathEffect = dash)
                                drawLine(outline, Offset(l, cy), Offset(0f, cy), px, pathEffect = dash)
                                drawLine(outline, Offset(r, cy), Offset(size.width, cy), px, pathEffect = dash)
                            }
                        }
                    }
                }

                if (bounds != null && iconBitmap != null) {
                    val bw = iconBitmap.width.toFloat()
                    val bh = iconBitmap.height.toFloat()
                    val top = (bounds.top / bh * 100).roundToInt()
                    val bottom = ((bh - bounds.bottom) / bh * 100).roundToInt()
                    val left = (bounds.left / bw * 100).roundToInt()
                    val right = ((bw - bounds.right) / bw * 100).roundToInt()
                    Text(
                        text = "↑ $top%    ↓ $bottom%    ← $left%    → $right%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.centerIcon),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = autoCenter, onCheckedChange = onAutoCenterChange)
                }

                Text(
                    text = stringResource(R.string.positionHorizontal),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = offsetX,
                    onValueChange = onOffsetXChange,
                    valueRange = -0.5f..0.5f,
                    track = { SliderDefaults.CenteredTrack(sliderState = it) }
                )
                Text(
                    text = stringResource(R.string.positionVertical),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = offsetY,
                    onValueChange = onOffsetYChange,
                    valueRange = -0.5f..0.5f,
                    track = { SliderDefaults.CenteredTrack(sliderState = it) }
                )
            }
        }
    )
}
