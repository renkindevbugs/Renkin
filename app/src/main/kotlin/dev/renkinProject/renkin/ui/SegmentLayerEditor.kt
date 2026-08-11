@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.SegmentLayer
import dev.renkinProject.renkin.icon.creator.applySegmentLayers
import dev.renkinProject.renkin.ui.theme.FieldShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Colorize segments modifier: a stack of "these regions, this colour" layers. Each layer owns
 * its own pick and its own style, so one icon can take a blue background and a yellow glyph.
 */
@Composable
internal fun SegmentLayerEditor(
    source: Bitmap,
    sampleBitmap: Bitmap?,
    layers: List<SegmentLayer>,
    onLayersChange: (List<SegmentLayer>) -> Unit,
    renderLayersPreview: (suspend (Int, ColorizerStyle) -> Bitmap?)?
) {
    var selected by remember { mutableIntStateOf(0) }
    var sheetOpen by remember { mutableStateOf(false) }

    // An empty stack still needs something to edit. Seeding it is a side effect, so it happens
    // in an effect — writing state straight from composition invites a recomposition loop.
    LaunchedEffect(layers.isEmpty()) {
        if (layers.isEmpty()) {
            onLayersChange(
                listOf(SegmentLayer(targets = emptyList(), style = defaultLayerStyle()))
            )
        }
    }
    if (layers.isEmpty()) return
    val index = selected.coerceIn(0, layers.lastIndex)
    val layer = layers[index]

    fun updateLayer(transform: (SegmentLayer) -> SegmentLayer) {
        onLayersChange(layers.mapIndexed { i, existing ->
            if (i == index) transform(existing) else existing
        })
    }

    fun removeLayer(removeIndex: Int) {
        val result = removeSegmentLayer(layers, index, removeIndex) ?: return
        onLayersChange(result.layers)
        selected = result.selectedIndex
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            layers.forEachIndexed { i, entry ->
                FilterChip(
                    selected = i == index,
                    onClick = { selected = i },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .colorizerSwatch(entry.style)
                        )
                    },
                    trailingIcon = if (layers.size > 1) {
                        {
                            // IconButton would expand to a 48 dp hit target and overlap the
                            // neighbouring chip. Keep this action inside its own chip instead.
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.segmentLayerRemove),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button) { removeLayer(i) }
                            )
                        }
                    } else {
                        null
                    },
                    label = { Text(stringResource(R.string.segmentLayerName, i + 1)) }
                )
            }
        }

        // Regions are picked on what the earlier layers already painted, exactly as
        // applySegmentLayers matches them. Showing the untouched artwork here offered colours
        // that are no longer visible, and picking one repainted over the earlier layer.
        // Keyed on the earlier layers alone: editing THIS layer's own pick must not rebuild the
        // bitmap the picker is working on, or every tap would recompute the palette under it.
        val earlier = remember(layers, index) {
            layers.take(index).filter { it.targets.isNotEmpty() }
        }
        val pickSource by produceState(source, source, earlier) {
            value = if (earlier.isEmpty()) {
                source
            } else {
                withContext(Dispatchers.Default) { applySegmentLayers(source, earlier) }
            }
        }

        SegmentSelector(
            source = pickSource,
            targets = layer.targets,
            tolerance = layer.tolerance,
            onTargetsChange = { targets -> updateLayer { it.copy(targets = targets) } },
            onToleranceChange = { tolerance -> updateLayer { it.copy(tolerance = tolerance) } }
        )

        ColorStyleCard(
            label = stringResource(R.string.colorize),
            style = layer.style,
            onClick = { sheetOpen = true }
        )

        OutlinedButton(
            onClick = {
                onLayersChange(
                    layers + SegmentLayer(targets = emptyList(), style = defaultLayerStyle())
                )
                selected = layers.size
            },
            shape = FieldShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null)
            Text(
                text = stringResource(R.string.segmentLayerAdd),
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Text(
            text = stringResource(R.string.segmentLayerHint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }

    if (sheetOpen) {
        ColorStyleSheet(
            title = stringResource(R.string.colorize),
            initialStyle = layer.style,
            sampleBitmap = sampleBitmap,
            // The preview renders the whole stack with this layer's draft substituted in.
            renderPreview = renderLayersPreview?.let { render -> { style -> render(index, style) } },
            onDismiss = { sheetOpen = false },
            onApply = { style ->
                updateLayer { it.copy(style = style) }
                sheetOpen = false
            }
        )
    }
}

/** The layer list and valid selection resulting from a structural editor operation. */
internal data class SegmentLayerSelection(
    val layers: List<SegmentLayer>,
    val selectedIndex: Int
)

/** Removes one layer while preserving which logical neighbour remains selected. */
internal fun removeSegmentLayer(
    layers: List<SegmentLayer>,
    selectedIndex: Int,
    removeIndex: Int
): SegmentLayerSelection? {
    if (layers.size <= 1 || removeIndex !in layers.indices) return null
    val remaining = layers.filterIndexed { index, _ -> index != removeIndex }
    val current = selectedIndex.coerceIn(layers.indices)
    val nextSelected = when {
        removeIndex < current -> current - 1
        removeIndex == current -> current.coerceAtMost(remaining.lastIndex)
        else -> current
    }
    return SegmentLayerSelection(remaining, nextSelected)
}

private fun defaultLayerStyle() = ColorizerStyle(firstColor = android.graphics.Color.WHITE)
