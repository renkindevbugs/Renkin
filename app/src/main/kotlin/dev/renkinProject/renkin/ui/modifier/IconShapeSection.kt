@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.renkinProject.renkin.ui.modifier

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.IconShapes
import dev.renkinProject.renkin.ui.AdjustmentState
import dev.renkinProject.renkin.ui.ColorStyleCard
import dev.renkinProject.renkin.ui.ColorStyleSheet
import dev.renkinProject.renkin.ui.LabeledSlider
import dev.renkinProject.renkin.ui.OptionGroup
import dev.renkinProject.renkin.ui.RenkinTooltipBox
import dev.renkinProject.renkin.ui.percentRuler

@Composable
internal fun IconShapeSection(
    adjustments: AdjustmentState,
    sampleBitmap: Bitmap?,
    renderPreview: (suspend (ColorizerStyle) -> Bitmap?)?
) {
    var colorSheetOpen by remember { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.iconShapeTitle),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.onSurface
    )
    OptionGroup {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconShape.entries.forEach { shape ->
                ShapeSwatch(
                    shape = shape,
                    selected = adjustments.iconShape == shape,
                    onClick = { adjustments.iconShape = shape }
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(visible = adjustments.iconShape != IconShape.NONE) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = adjustments.shapeCrop,
                        onClick = { adjustments.shapeCrop = true },
                        label = { Text(stringResource(R.string.shapeCrop)) }
                    )
                    FilterChip(
                        selected = !adjustments.shapeCrop,
                        onClick = { adjustments.shapeCrop = false },
                        label = { Text(stringResource(R.string.shapePlate)) }
                    )
                }
                LabeledSlider(
                    label = stringResource(R.string.shapeIconScale),
                    value = adjustments.shapeScale,
                    onValueChange = { adjustments.shapeScale = it },
                    valueRange = 0.5f..1.5f,
                    centered = true,
                    ruler = percentRuler()
                )
                if (!adjustments.shapeCrop) {
                    val shapeStyle = adjustments.shapeStyle
                    ColorStyleCard(
                        label = stringResource(R.string.shapeColor),
                        style = shapeStyle,
                        onClick = { colorSheetOpen = true }
                    )
                    if (colorSheetOpen) {
                        ColorStyleSheet(
                            title = stringResource(R.string.shapeColor),
                            initialStyle = shapeStyle,
                            sampleBitmap = sampleBitmap,
                            renderPreview = renderPreview,
                            showSingleColorEffects = false,
                            onDismiss = { colorSheetOpen = false },
                            onApply = { style ->
                                adjustments.shapeStyle = style
                                colorSheetOpen = false
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One selectable shape preview. The path comes from the same [IconShapes] geometry the
 * generator uses, so what's drawn here is exactly what the icon gets.
 */
@Composable
internal fun ShapeSwatch(shape: IconShape, selected: Boolean, onClick: () -> Unit) {
    val label = shapeLabel(shape)
    val fill = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    RenkinTooltipBox(label) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else Modifier
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (shape == IconShape.NONE) {
                Icon(
                    imageVector = Icons.Filled.Block,
                    contentDescription = label,
                    tint = fill,
                    modifier = Modifier.size(26.dp)
                )
            } else {
                Canvas(Modifier.size(30.dp)) {
                    IconShapes.path(shape, size.minDimension)?.let {
                        drawPath(it.asComposePath(), color = fill)
                    }
                }
            }
        }
    }
}

@Composable
internal fun shapeLabel(shape: IconShape): String = stringResource(
    when (shape) {
        IconShape.NONE -> R.string.shapeNone
        IconShape.CIRCLE -> R.string.shapeCircle
        IconShape.SQUIRCLE -> R.string.shapeSquircle
        IconShape.PEBBLE -> R.string.shapePebble
        IconShape.COOKIE -> R.string.shapeCookie
        IconShape.SUNNY -> R.string.shapeSunny
    }
)
