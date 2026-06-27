@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.alembiconsProject.alembicons.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.ui.theme.CardShape
import dev.alembiconsProject.alembicons.data.ImageEdit
import dev.alembiconsProject.alembicons.data.Source
import dev.alembiconsProject.alembicons.data.getImageEditLabels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.text.font.FontWeight

@Composable
internal fun ModifierTab(
    source: Source,
    imageEdit: ImageEdit,
    iconColor: Color,
    useVector: Boolean,
    useMonochrome: Boolean,
    edgeThreshold: Float,
    edgeSmoothing: Float,
    edgeContrast: Boolean,
    iconScale: Float,
    onImageEditChange: (ImageEdit) -> Unit,
    onColorChange: (Color) -> Unit,
    onVectorChange: (Boolean) -> Unit,
    onMonochromeChange: (Boolean) -> Unit,
    onEdgeThresholdChange: (Float) -> Unit,
    onEdgeSmoothingChange: (Float) -> Unit,
    onEdgeContrastChange: (Boolean) -> Unit,
    onIconScaleChange: (Float) -> Unit
) {
    val editLabels = getImageEditLabels()
    var colorPickerOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.imageEdit),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        editLabels.forEach { (edit, label) ->
            val selected = imageEdit == edit
            Surface(
                onClick = { onImageEditChange(edit) },
                shape = CardShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Done,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        if (imageEdit == ImageEdit.EDGE) {
            Surface(
                shape = CardShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    // Detail: inverse of the Canny threshold — right = more edges kept
                    val detail = 1f - (edgeThreshold - 0.5f) / 4.5f
                    Text(
                        text = stringResource(R.string.edgeDetail),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = detail.coerceIn(0f, 1f),
                        onValueChange = { onEdgeThresholdChange(0.5f + (1f - it) * 4.5f) },
                        valueRange = 0f..1f
                    )
                    Text(
                        text = stringResource(R.string.edgeSmoothing),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = edgeSmoothing,
                        onValueChange = { onEdgeSmoothingChange(it) },
                        valueRange = 0.5f..4f
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.edgeContrast),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = edgeContrast,
                            onCheckedChange = { onEdgeContrastChange(it) }
                        )
                    }
                }
            }
        }

        if (imageEdit != ImageEdit.NONE) {
            Surface(
                onClick = { colorPickerOpen = true },
                shape = CardShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.iconColor),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = CircleShape,
                        color = iconColor,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.size(28.dp)
                    ) {}
                }
            }
        }

        if (isPathTracingEnabled(source, imageEdit)) {
            Surface(
                shape = CardShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    VectorSwitch(useVector) { onVectorChange(it) }
                    MonochromeSwitch(useMonochrome) { onMonochromeChange(it) }
                }
            }
        }

        // Per-icon adjustments, independent of the modifier chosen above
        Text(
            text = stringResource(R.string.adjustments),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Surface(
            shape = CardShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.iconScale),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${(iconScale * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // Range centred on 1.0: left shrinks (padding), right enlarges (zoom).
                // Official M3 centered track fills from the middle outwards.
                Slider(
                    value = iconScale,
                    onValueChange = { onIconScaleChange(it) },
                    valueRange = 0.5f..1.5f,
                    track = { SliderDefaults.CenteredTrack(sliderState = it) }
                )
            }
        }
    }

    if (colorPickerOpen) {
        ColorDialog(
            onDismiss = { colorPickerOpen = false },
            currentlySelected = iconColor,
            onColorSelected = { onColorChange(it) }
        )
    }

}
