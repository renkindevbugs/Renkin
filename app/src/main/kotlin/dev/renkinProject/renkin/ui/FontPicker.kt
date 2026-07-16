package dev.renkinProject.renkin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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

@Composable
private fun FontPickerDialog(selectedPath: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    // Scanning /system/fonts is I/O — tiny, but off the main thread anyway.
    val fonts by produceState(listOf(FontCatalog.DEFAULT)) {
        value = withContext(Dispatchers.IO) { listOf(FontCatalog.DEFAULT) + FontCatalog.systemFonts() }
    }

    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.textFont)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(fonts, key = { it.path }) { font ->
                    val family = remember(font.path) {
                        font.path.takeIf { it.isNotEmpty() }
                            ?.let { path -> FontCatalog.typeface(path)?.let { FontFamily(it) } }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(font.path) }
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    )
}
