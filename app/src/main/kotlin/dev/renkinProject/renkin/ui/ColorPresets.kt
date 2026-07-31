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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.renkinProject.renkin.icon.creator.evenGradientPositions
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

/**
 * The saved colour holding the same colours as [style], if there is one. Only the colours and
 * their positions are compared: turning a saved gradient radial or spinning its angle is a view
 * of the same colours, while repainting or dragging a stop makes it a different one.
 */
fun savedPresetMatching(presets: List<ColorPreset>, style: ColorizerStyle): ColorPreset? =
    presets.firstOrNull { preset ->
        decodeColorizerStyle(preset.style)?.let { saved ->
            saved.mode == style.mode &&
                saved.allGradientColors == style.allGradientColors &&
                comparablePositions(saved) == comparablePositions(style)
        } == true
    }

/** No positions and an even spread are the same gradient, so they must compare equal. */
private fun comparablePositions(style: ColorizerStyle): List<Float> =
    style.gradientPositions.takeIf { it.size == style.allGradientColors.size }
        ?: evenGradientPositions(style.allGradientColors.size)

/** How the saved-colour list is ordered. Newest first is the default: it is what was just saved. */
enum class ColorPresetSort {
    NEWEST,
    OLDEST,
    NAME
}

/** The rows the saved-colour dialog shows: name search first, then the chosen order. */
fun sortedColorPresets(
    presets: List<ColorPreset>,
    query: String,
    sort: ColorPresetSort
): List<ColorPreset> {
    val trimmed = query.trim()
    val matching = if (trimmed.isEmpty()) {
        presets
    } else {
        presets.filter { it.name.contains(trimmed, ignoreCase = true) }
    }
    return when (sort) {
        ColorPresetSort.NEWEST -> matching.sortedByDescending { it.createdAt }
        ColorPresetSort.OLDEST -> matching.sortedBy { it.createdAt }
        ColorPresetSort.NAME -> matching.sortedBy { it.name.lowercase() }
    }
}

/** Picker over the saved colours: tap one to apply it to the sheet's draft, × to delete it. */
@Composable
internal fun ColorPresetDialog(
    onPick: (ColorizerStyle) -> Unit,
    onDismiss: () -> Unit
) {
    val store = LocalColorPresets.current
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ColorPresetSort.NEWEST) }
    val shown = remember(store.presets, query, sort) {
        sortedColorPresets(store.presets, query, sort)
    }

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
                    // Searching a handful of colours would be noise; the controls appear once the
                    // library is big enough to actually need them.
                    if (store.presets.size >= SEARCHABLE_PRESET_COUNT) {
                        SearchField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = stringResource(R.string.savedColorsSearch),
                            modifier = Modifier.fillMaxWidth(),
                            extraTrailing = {
                                ColorPresetSortMenu(sort = sort, onSortChange = { sort = it })
                            }
                        )
                    }
                    if (shown.isEmpty()) {
                        Text(
                            text = stringResource(R.string.savedColorsNoMatch),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shown, key = { it.id }) { preset ->
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

/**
 * Bookmark that lights up while [style]'s colours are in the library and removes them again when
 * tapped. The state is derived from the library, not remembered locally, so repainting a stop or
 * dragging it puts the icon out on its own — while switching to radial or spinning the angle,
 * which describe the same colours, leaves it lit.
 */
@Composable
internal fun SavedColorToggle(
    name: String,
    style: ColorizerStyle,
    modifier: Modifier = Modifier
) {
    val store = LocalColorPresets.current
    val saved = remember(store.presets, style) { savedPresetMatching(store.presets, style) }

    IconButton(
        onClick = { saved?.let { store.delete(it.id) } ?: store.save(name, style) },
        modifier = modifier
    ) {
        Icon(
            imageVector = if (saved != null) Icons.Filled.Bookmark else Icons.Filled.BookmarkAdd,
            contentDescription = stringResource(
                if (saved != null) R.string.savedColorsRemoveTitle else R.string.savedColorsSaveTitle
            ),
            tint = if (saved != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/** Sort choice for the saved-colour list, living inside the search field like the home list's. */
@Composable
private fun ColorPresetSortMenu(
    sort: ColorPresetSort,
    onSortChange: (ColorPresetSort) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(R.string.savedColorsSort)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ColorPresetSort.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(stringResource(entry.labelRes())) },
                    onClick = {
                        onSortChange(entry)
                        open = false
                    },
                    leadingIcon = {
                        if (entry == sort) {
                            Icon(imageVector = Icons.Filled.Check, contentDescription = null)
                        }
                    }
                )
            }
        }
    }
}

private fun ColorPresetSort.labelRes(): Int = when (this) {
    ColorPresetSort.NEWEST -> R.string.savedColorsSortNewest
    ColorPresetSort.OLDEST -> R.string.savedColorsSortOldest
    ColorPresetSort.NAME -> R.string.savedColorsSortName
}

// Below this many saved colours the search field and sort menu are more clutter than help.
private const val SEARCHABLE_PRESET_COUNT = 6

/** Name prompt for saving the current colour; prefilled with the next free "Color N". */
@Composable
internal fun SaveColorPresetDialog(
    style: ColorizerStyle,
    onDismiss: () -> Unit
) {
    val store = LocalColorPresets.current
    val defaultName = stringResource(R.string.savedColorsDefaultName, store.presets.size + 1)
    var name by remember { mutableStateOf(defaultName) }

    RenkinAlertDialog(
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
