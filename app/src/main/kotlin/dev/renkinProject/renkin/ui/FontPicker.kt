package dev.renkinProject.renkin.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.icon.creator.FontCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The text-icon font row: shows the picked font's name and opens a list of the bundled
 * default plus the device's system fonts, each previewed in its own typeface. [selectedPath]
 * is the TTF/OTF path ("" = Arcticons Sans).
 */
@Composable
fun FontPickerRow(selectedPath: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val effectivePath by produceState("", selectedPath) {
        value = withContext(Dispatchers.IO) { FontCatalog.usablePathOrDefault(selectedPath) }
    }
    val selectedLabel = if (effectivePath.isEmpty()) FontCatalog.DEFAULT.label
        else remember(effectivePath) { FontCatalog.prettyLabelFor(effectivePath) }

    OptionCard(
        label = stringResource(R.string.textFont),
        onClick = { open = true },
        trailing = {
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )

    if (open) {
        FontPickerDialog(
            selectedPath = effectivePath,
            onSelect = {
                onChange(it)
                open = false
            },
            onDismiss = { open = false }
        )
    }
}

/** The font rows to show: the search query narrows by name, the catalogue order is kept. */
internal fun pickerFonts(
    fonts: List<FontCatalog.FontChoice>,
    query: String
): List<FontCatalog.FontChoice> {
    val trimmed = query.trim()
    return if (trimmed.isEmpty()) fonts
    else fonts.filter { it.label.contains(trimmed, ignoreCase = true) }
}

@Composable
private fun FontPickerDialog(selectedPath: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    // Scanning /system/fonts is I/O — tiny, but off the main thread anyway.
    val fonts by produceState(listOf(FontCatalog.DEFAULT)) {
        value = withContext(Dispatchers.IO) { listOf(FontCatalog.DEFAULT) + FontCatalog.systemFonts() }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val shownFonts = remember(fonts, query) { pickerFonts(fonts, query) }

    // Same reasoning as the pack picker: a device can list dozens of system fonts, and the
    // picked one is often well below the fold. Skipped while searching.
    val listState = rememberLazyListState()
    val selectedIndex = remember(shownFonts, selectedPath, query) {
        if (query.trim().isNotEmpty()) -1
        else shownFonts.indexOfFirst { it.path == selectedPath }
    }
    LaunchedEffect(shownFonts.size) {
        if (selectedIndex >= 0) listState.scrollToItem(selectedIndex)
    }

    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.textFont)) },
        text = {
            Column {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.searchFonts),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                if (shownFonts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.searchFontsEmpty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .drawVerticalScrollbar(listState)
                ) {
                items(shownFonts, key = { it.path }) { font ->
                    val family = remember(font.path) {
                        font.path.takeIf { it.isNotEmpty() }
                            ?.let { path -> FontCatalog.typeface(path)?.let { FontFamily(it) } }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // selectable, not clickable: TalkBack then announces the row as a
                            // radio button and says which one is selected. The check icon is
                            // decoration on top of that, so it stays contentDescription-free.
                            .selectable(
                                selected = font.path == selectedPath,
                                role = Role.RadioButton
                            ) { onSelect(font.path) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Preview glyphs in the font itself — the name alone says nothing.
                        Text(
                            text = "Ag",
                            fontSize = 20.sp,
                            fontFamily = family,
                            modifier = Modifier.width(40.dp)
                        )
                        Text(
                            text = font.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        )
                        if (font.path == selectedPath) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    )
}
