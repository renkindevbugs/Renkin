@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.renkinProject.renkin.ui.modifier

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.ui.AdjustmentState
import dev.renkinProject.renkin.ui.ColorStyleCard
import dev.renkinProject.renkin.ui.ColorStyleSheet
import dev.renkinProject.renkin.ui.LabeledSlider
import dev.renkinProject.renkin.ui.OptionCard
import dev.renkinProject.renkin.ui.OptionGroup
import dev.renkinProject.renkin.ui.pixelRuler

@Composable
internal fun OutlineSection(
    adjustments: AdjustmentState,
    sampleBitmap: Bitmap?,
    renderPreview: (suspend (ColorizerStyle) -> Bitmap?)?,
    onOpenEraser: () -> Unit
) {
    var colorSheetOpen by rememberSaveable { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.outlineTitle),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.onSurface
    )
    OptionGroup {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineMode.entries.forEach { mode ->
                FilterChip(
                    selected = adjustments.outlineMode == mode,
                    onClick = { adjustments.outlineMode = mode },
                    label = {
                        Text(
                            stringResource(
                                when (mode) {
                                    OutlineMode.NONE -> R.string.outlineNone
                                    OutlineMode.ADD -> R.string.outlineAdd
                                    OutlineMode.RECOLOR -> R.string.outlineRecolor
                                }
                            )
                        )
                    }
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(visible = adjustments.outlineMode != OutlineMode.NONE) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (adjustments.outlineMode == OutlineMode.ADD) {
                    LabeledSlider(
                        label = stringResource(R.string.outlineThickness),
                        value = adjustments.outlineWidth,
                        onValueChange = { adjustments.outlineWidth = it },
                        valueRange = 1f..16f,
                        ruler = pixelRuler()
                    )
                }
                val outlineStyle = adjustments.outlineStyle
                ColorStyleCard(
                    label = stringResource(R.string.outlineColor),
                    style = outlineStyle,
                    onClick = { colorSheetOpen = true }
                )
                if (colorSheetOpen) {
                    ColorStyleSheet(
                        title = stringResource(R.string.outlineColor),
                        initialStyle = outlineStyle,
                        sampleBitmap = sampleBitmap,
                        showSingleColorEffects = false,
                        renderPreview = renderPreview,
                        onDismiss = { colorSheetOpen = false },
                        onApply = { style ->
                            adjustments.outlineStyle = style
                            colorSheetOpen = false
                        }
                    )
                }
                OptionCard(
                    label = stringResource(R.string.eraseTitle),
                    onClick = onOpenEraser,
                    trailing = {
                        Text(
                            text = if (adjustments.eraseStrokes.isEmpty()) {
                                stringResource(R.string.positionDefault)
                            } else {
                                stringResource(R.string.eraseCount, adjustments.eraseStrokes.size)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                )
            }
        }
    }
}
