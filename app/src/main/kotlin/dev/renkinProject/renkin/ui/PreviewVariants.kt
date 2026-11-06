@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.ui.theme.InnerShape
import dev.renkinProject.renkin.util.runCatchingCancellable

/**
 * The looks a rotating icon takes on the launcher (calendar days, clock times), shown as a strip
 * in the enlarged New preview. [selected] is only a preview choice: Apply keeps the draft.
 * [draftIndex] is the variant the draft itself shows (the picked calendar day), if any.
 * [renderKey] changes whenever the rendering inputs change, which drops cached thumbnails.
 */
@Immutable
internal class PreviewVariants(
    val kind: RotatingIconKind,
    val labels: List<String>,
    val selected: Int?,
    val draftIndex: Int?,
    val renderKey: Any,
    val onSelect: (Int) -> Unit,
    val render: suspend (Int) -> IconPackDrawable?,
    val note: String? = null
) {
    /** The previewed variant when it differs from what the draft itself shows. */
    val shownVariant: Int? get() = selected?.takeIf { labels.isNotEmpty() && it != draftIndex }
}

internal enum class RotatingIconKind(val icon: ImageVector) {
    CALENDAR(Icons.Filled.DateRange),
    CLOCK(Icons.Filled.Schedule)
}

/** Marks a calendar-rotation or live-clock icon, in the pack browser and on the New slot. */
@Composable
internal fun RotatingIconBadge(kind: RotatingIconKind, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = kind.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(size * 5 / 8)
        )
    }
}

@Composable
internal fun PreviewVariantStrip(variants: PreviewVariants, mask: Shape) {
    val cache = remember(variants.renderKey) { mutableStateMapOf<Int, IconPackDrawable>() }
    val focus = variants.selected ?: variants.draftIndex
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = ((focus ?: 0) - 2).coerceAtLeast(0))
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(variants.labels, key = { index, _ -> index }) { index, label ->
            key(variants.renderKey) {
                val icon by produceState(cache[index], variants.renderKey, index) {
                    if (value == null) {
                        value = runCatchingCancellable { variants.render(index) }.getOrNull()?.also { cache[index] = it }
                    }
                }
                PreviewVariantChip(label, icon, mask, selected = index == focus) { variants.onSelect(index) }
            }
        }
    }
}

@Composable
private fun PreviewVariantChip(
    label: String,
    icon: IconPackDrawable?,
    mask: Shape,
    selected: Boolean,
    onClick: () -> Unit
) {
    val fill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "previewVariantSelection"
    )
    Column(
        Modifier.width(56.dp).clip(InnerShape).background(fill)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(Modifier.size(40.dp).clip(mask).background(MaterialTheme.colorScheme.surfaceVariant)) {
            icon?.let { Image(it.getPainter(), contentDescription = null, modifier = Modifier.size(40.dp)) }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR_NUMBERS),
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

private const val TABULAR_NUMBERS = "tnum"
