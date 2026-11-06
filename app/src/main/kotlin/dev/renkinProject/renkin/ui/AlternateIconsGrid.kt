@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.packages.AlternateApplicationIcon
import dev.renkinProject.renkin.ui.theme.IconShape
import dev.renkinProject.renkin.ui.theme.InnerShape

private const val ALTERNATE_ICON_COLUMNS = 5
private const val COLLAPSED_ALTERNATE_ICON_ROWS = 2

/** The app's own launcher icons (activity-aliases); the first one is the row's current icon. */
@Composable
internal fun AlternateIconsGrid(
    icons: List<AlternateApplicationIcon>,
    selectedComponent: String,
    missingSelection: Boolean,
    onSelect: (String) -> Unit,
    loadPreview: suspend (AlternateApplicationIcon, Int) -> ImageBitmap?,
    modifier: Modifier = Modifier
) {
    val collapsedCount = ALTERNATE_ICON_COLUMNS * COLLAPSED_ALTERNATE_ICON_ROWS
    val selectedIndex = icons.indexOfFirst { it.component == selectedComponent }.coerceAtLeast(0)
    // A selection hidden in the collapsed rows would leave the user unable to see what is applied.
    var expanded by rememberSaveable(icons.size) { mutableStateOf(selectedIndex >= collapsedCount) }
    val shown = if (expanded) icons else icons.take(collapsedCount)
    Column(modifier) {
        Text(
            text = stringResource(R.string.alternateIconsTitle),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.alternateIconsHint, icons.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
        )
        shown.chunked(ALTERNATE_ICON_COLUMNS).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { column, icon ->
                    val index = rowIndex * ALTERNATE_ICON_COLUMNS + column
                    AlternateIconChip(
                        icon = icon,
                        caption = if (index == 0) stringResource(R.string.iconCurrent) else "",
                        description = icon.label ?: stringResource(R.string.alternateIconDescription, index + 1, icons.size),
                        selected = index == selectedIndex,
                        loadPreview = loadPreview,
                        onClick = { onSelect(icon.component) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(ALTERNATE_ICON_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (icons.size > collapsedCount) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    if (expanded) stringResource(R.string.alternateIconShowLess)
                    else stringResource(R.string.alternateIconShowAll, icons.size)
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp).size(18.dp)
                )
            }
        }
        if (missingSelection) {
            Text(
                text = stringResource(R.string.alternateIconMissing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun AlternateIconChip(
    icon: AlternateApplicationIcon,
    caption: String,
    description: String,
    selected: Boolean,
    loadPreview: suspend (AlternateApplicationIcon, Int) -> ImageBitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizePx = with(LocalDensity.current) { 40.dp.roundToPx() }
    val preview by produceState<ImageBitmap?>(null, icon, sizePx) { value = loadPreview(icon, sizePx) }
    val fill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "alternateIconSelection"
    )
    Column(
        modifier
            .clip(InnerShape)
            .background(fill)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(Modifier.size(40.dp).clip(IconShape)) {
            preview?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) }
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
