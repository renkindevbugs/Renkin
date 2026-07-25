@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.renkinProject.renkin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.ColorPreset
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.decodeColorizerStyle
import dev.renkinProject.renkin.icon.creator.encodeColorizerStyle
import dev.renkinProject.renkin.ui.theme.DialogShape
import dev.renkinProject.renkin.ui.theme.InnerShape

/**
 * The saved-colour library, reached from every colour sheet. Backed by the view model so the
 * sheet itself never touches storage; the no-op default keeps previews and tests composable.
 */
interface ColorPresetStore {
    val presets: List<ColorPreset>
    fun save(name: String, style: ColorizerStyle)
    fun delete(id: Long)
}

private object NoColorPresets : ColorPresetStore {
    override val presets: List<ColorPreset> = emptyList()
    override fun save(name: String, style: ColorizerStyle) = Unit
    override fun delete(id: Long) = Unit
}

val LocalColorPresets = compositionLocalOf<ColorPresetStore> { NoColorPresets }

/** Convenience for activities: wraps [content] with a store backed by the given callbacks. */
@Composable
fun ProvideColorPresets(
    presets: List<ColorPreset>,
    onSave: (String, String) -> Unit,
    onDelete: (Long) -> Unit,
    content: @Composable () -> Unit
) {
    val store = remember(presets) {
        object : ColorPresetStore {
            override val presets: List<ColorPreset> = presets
            override fun save(name: String, style: ColorizerStyle) =
                onSave(name, encodeColorizerStyle(style))
            override fun delete(id: Long) = onDelete(id)
        }
    }
    CompositionLocalProvider(LocalColorPresets provides store, content = content)
}

/** Picker over the saved colours: tap one to apply it to the sheet's draft, × to delete it. */
@Composable
internal fun ColorPresetDialog(
    onPick: (ColorizerStyle) -> Unit,
    onDismiss: () -> Unit
) {
    val store = LocalColorPresets.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = DialogShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.savedColorsTitle),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (store.presets.isEmpty()) {
                    Text(
                        text = stringResource(R.string.savedColorsEmpty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(store.presets, key = { it.id }) { preset ->
                            val style = remember(preset.style) {
                                decodeColorizerStyle(preset.style)
                            }
                            Surface(
                                shape = InnerShape,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                onClick = { style?.let(onPick) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        start = 10.dp,
                                        end = 4.dp,
                                        top = 6.dp,
                                        bottom = 6.dp
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // The colour itself is the label people actually read.
                                    Box(
                                        modifier = Modifier
                                            .size(width = 44.dp, height = 28.dp)
                                            .clip(InnerShape)
                                            .then(
                                                if (style != null) {
                                                    Modifier.colorizerSwatch(style)
                                                } else Modifier
                                            )
                                    )
                                    Text(
                                        text = preset.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { store.delete(preset.id) }) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = stringResource(
                                                R.string.savedColorsDelete
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}

/** Name prompt for saving the current colour; prefilled with the next free "Color N". */
@Composable
internal fun SaveColorPresetDialog(
    style: ColorizerStyle,
    onDismiss: () -> Unit
) {
    val store = LocalColorPresets.current
    val defaultName = stringResource(R.string.savedColorsDefaultName, store.presets.size + 1)
    var name by remember { mutableStateOf(defaultName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.savedColorsSaveTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(InnerShape)
                        .colorizerSwatch(style)
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.savedColorsName)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Done
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    store.save(name.trim().ifEmpty { defaultName }, style)
                    onDismiss()
                }
            ) { Text(stringResource(R.string.saveAction)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.colorizeCancel)) }
        }
    )
}
