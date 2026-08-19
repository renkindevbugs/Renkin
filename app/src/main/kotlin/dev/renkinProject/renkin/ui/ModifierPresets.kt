@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package dev.renkinProject.renkin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.ModifierPreset
import dev.renkinProject.renkin.data.getImageEditLabels
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.ModifierPresetPayload
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.decodeModifierPreset
import dev.renkinProject.renkin.icon.creator.encodeModifierPreset
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.FieldShape
import dev.renkinProject.renkin.ui.theme.InnerShape
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The saved Modifier-preset library, reached from the Modifier tab. Backed by the view model so
 * the tab itself never touches storage; the no-op default keeps previews and tests composable —
 * the same contract [ColorPresetStore] uses for saved colours.
 */
interface ModifierPresetStore {
    val presets: List<ModifierPreset>
    fun save(name: String, payload: ModifierPresetPayload)
    fun update(id: Long, payload: ModifierPresetPayload)
    fun rename(id: Long, name: String)
    fun markUsed(id: Long)
    fun delete(id: Long)
}

private object NoModifierPresets : ModifierPresetStore {
    override val presets: List<ModifierPreset> = emptyList()
    override fun save(name: String, payload: ModifierPresetPayload) = Unit
    override fun update(id: Long, payload: ModifierPresetPayload) = Unit
    override fun rename(id: Long, name: String) = Unit
    override fun markUsed(id: Long) = Unit
    override fun delete(id: Long) = Unit
}

val LocalModifierPresets = compositionLocalOf<ModifierPresetStore> { NoModifierPresets }

/** Convenience for activities: wraps [content] with a store backed by the given callbacks. */
@Composable
fun ProvideModifierPresets(
    presets: List<ModifierPreset>,
    onSave: (name: String, payload: String, schemaVersion: Int) -> Unit,
    onUpdate: (id: Long, payload: String, schemaVersion: Int) -> Unit,
    onRename: (Long, String) -> Unit,
    onMarkUsed: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    content: @Composable () -> Unit
) {
    val store = remember(presets) {
        object : ModifierPresetStore {
            override val presets: List<ModifierPreset> = presets
            override fun save(name: String, payload: ModifierPresetPayload) =
                onSave(name, encodeModifierPreset(payload), payload.schemaVersion)
            override fun update(id: Long, payload: ModifierPresetPayload) =
                onUpdate(id, encodeModifierPreset(payload), payload.schemaVersion)
            override fun rename(id: Long, name: String) = onRename(id, name)
            override fun markUsed(id: Long) = onMarkUsed(id)
            override fun delete(id: Long) = onDelete(id)
        }
    }
    CompositionLocalProvider(LocalModifierPresets provides store, content = content)
}

/** How many presets the inline strip offers before the user has to open Manage. */
private const val RECENT_PRESET_COUNT = 3

/**
 * The Modifier tab's preset section: the most recent presets as one-tap tiles, the save entry
 * point, and the dialog that manages the whole library. Loading a preset only writes into the
 * editor's draft state — the tab's own Apply stays the single confirmation that touches the icon.
 */
@Composable
internal fun ModifierPresetsSection(
    adjustments: AdjustmentState,
    imageEdit: ImageEdit,
    iconColor: androidx.compose.ui.graphics.Color,
    previews: ModifierPreviews?,
    modifier: Modifier = Modifier,
    onPresetLoaded: (ModifierPresetApplication) -> Unit
) {
    val store = LocalModifierPresets.current
    val sessionKey = previews?.presetSourceKey
    var saveOpen by rememberSaveable(sessionKey) { mutableStateOf(false) }
    var manageOpen by rememberSaveable(sessionKey) { mutableStateOf(false) }
    // Selection is session state: it identifies the recipe loaded into this editing draft.
    var selectedPresetId by rememberSaveable(sessionKey) { mutableStateOf<Long?>(null) }
    var pendingSavedName by remember(sessionKey) { mutableStateOf<String?>(null) }
    var pendingSavedPayload by remember(sessionKey) { mutableStateOf<String?>(null) }
    var idsBeforePendingSave by remember(sessionKey) { mutableStateOf<Set<Long>>(emptySet()) }

    LaunchedEffect(store.presets, pendingSavedName, pendingSavedPayload, idsBeforePendingSave) {
        val name = pendingSavedName ?: return@LaunchedEffect
        val payload = pendingSavedPayload ?: return@LaunchedEffect
        store.presets.firstOrNull {
            it.id !in idsBeforePendingSave && it.name == name && it.payload == payload
        }?.let { saved ->
            selectedPresetId = saved.id
            pendingSavedName = null
            pendingSavedPayload = null
            idsBeforePendingSave = emptySet()
        }
    }
    LaunchedEffect(store.presets, selectedPresetId) {
        if (selectedPresetId != null && store.presets.none { it.id == selectedPresetId }) {
            selectedPresetId = null
        }
    }

    val load: (ModifierPreset) -> Unit = { preset ->
        decodeModifierPreset(preset.payload)?.let { payload ->
            onPresetLoaded(applyModifierPreset(payload, adjustments))
            store.markUsed(preset.id)
            selectedPresetId = preset.id
        }
    }
    val recent = remember(store.presets) { store.presets.take(RECENT_PRESET_COUNT) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.modifierPresetsTitle),
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { manageOpen = true }) {
                Text(stringResource(R.string.modifierPresetsManage))
            }
        }

        if (recent.isEmpty()) {
            Text(
                text = stringResource(R.string.modifierPresetsEmptyHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            recent.forEach { preset ->
                key(preset.id) {
                    ModifierPresetRow(
                        preset = preset,
                        selected = preset.id == selectedPresetId,
                        previews = previews,
                        onClick = { load(preset) },
                        onUpdate = if (preset.id == selectedPresetId) {
                            {
                                val existing = decodeModifierPreset(preset.payload)
                                if (existing != null) {
                                    store.update(
                                        preset.id,
                                        captureModifierPreset(
                                            adjustments,
                                            imageEdit,
                                            iconColor,
                                            existing.includedGroups()
                                        )
                                    )
                                }
                            }
                        } else null
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { saveOpen = true },
            shape = FieldShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.BookmarkAdd, contentDescription = null)
            Text(
                text = stringResource(R.string.modifierPresetsSaveCurrent),
                modifier = Modifier.padding(start = 8.dp)
            )
        }

    }

    if (saveOpen) {
        SaveModifierPresetDialog(
            adjustments = adjustments,
            imageEdit = imageEdit,
            iconColor = iconColor,
            onSave = { name, payload ->
                idsBeforePendingSave = store.presets.mapTo(mutableSetOf()) { it.id }
                store.save(name, payload)
                pendingSavedName = name
                pendingSavedPayload = encodeModifierPreset(payload)
                saveOpen = false
            },
            onDismiss = { saveOpen = false }
        )
    }

    if (manageOpen) {
        ModifierPresetsDialog(
            presets = store.presets,
            selectedPresetId = selectedPresetId,
            previews = previews,
            onPick = { preset ->
                load(preset)
                manageOpen = false
            },
            onDismiss = { manageOpen = false }
        )
    }

}

/**
 * The save dialog: a name, and one checkbox per group with the value it would store. Groups are
 * pre-ticked when they currently do something, but every one stays switchable — saving "no
 * outline" on purpose is what makes a preset able to turn a group OFF on the next icon.
 */
@Composable
private fun SaveModifierPresetDialog(
    adjustments: AdjustmentState,
    imageEdit: ImageEdit,
    iconColor: androidx.compose.ui.graphics.Color,
    onSave: (String, ModifierPresetPayload) -> Unit,
    onDismiss: () -> Unit
) {
    val store = LocalModifierPresets.current
    // Pre-filled so saving is one tap for anyone who does not care about naming; the field is
    // still focused-and-editable for anyone who does. Same idea as the numbered suffix an
    // imported profile gets, only here the number is the whole name.
    val defaultNamePrefix = stringResource(R.string.modifierPresetNamePrefix)
    var name by rememberSaveable {
        mutableStateOf(
            defaultModifierPresetName(store.presets.map { it.name }, defaultNamePrefix)
        )
    }
    // Saved as one string: a set of enums is not Bundle-storable, and the names keep the saved
    // form readable if this ever has to be debugged from a state dump.
    var groups by rememberSaveable(stateSaver = modifierPresetGroupsSaver()) {
        mutableStateOf(defaultPresetGroups(adjustments, imageEdit))
    }

    val effectLabel = getImageEditLabels(includeSegments = true)[imageEdit].orEmpty()
    val toggle: (ModifierPresetGroup) -> Unit = { group ->
        groups = if (group in groups) groups - group else groups + group
    }

    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modifierPresetsSaveTitle)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.modifierPresetName)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                shape = FieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.modifierPresetsInclude),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IncludeGroupRow(
                title = stringResource(R.string.modifierPresetGroupEffect),
                summary = effectLabel,
                checked = ModifierPresetGroup.EFFECT in groups,
                onToggle = { toggle(ModifierPresetGroup.EFFECT) }
            )
            IncludeGroupRow(
                title = stringResource(R.string.iconScale),
                summary = "${(adjustments.iconScale * 100).roundToInt()}%",
                checked = ModifierPresetGroup.ICON_SCALE in groups,
                onToggle = { toggle(ModifierPresetGroup.ICON_SCALE) }
            )
            IncludeGroupRow(
                title = stringResource(R.string.iconShapeTitle),
                summary = shapeSummary(adjustments),
                checked = ModifierPresetGroup.SHAPE in groups,
                onToggle = { toggle(ModifierPresetGroup.SHAPE) }
            )
            IncludeGroupRow(
                title = stringResource(R.string.outlineTitle),
                summary = outlineSummary(adjustments),
                checked = ModifierPresetGroup.OUTLINE in groups,
                onToggle = { toggle(ModifierPresetGroup.OUTLINE) }
            )
            Text(
                text = stringResource(R.string.modifierPresetPerIconNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        name.trim(),
                        captureModifierPreset(adjustments, imageEdit, iconColor, groups)
                    )
                },
                enabled = name.isNotBlank() && groups.isNotEmpty()
            ) { Text(stringResource(R.string.modifierPresetsSaveAction)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    )
}

@Composable
private fun IncludeGroupRow(
    title: String,
    summary: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() }
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // null callback: the row above owns the toggle semantics, so screen readers announce
            // one control instead of two.
            Checkbox(checked = checked, onCheckedChange = null)
            Column(
                Modifier
                    .padding(start = 10.dp)
                    .weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Browse and manage the library: search by name, tap to load, per-row rename and delete. Delete
 * confirms through the shared [ConfirmDialog], like every other destructive action.
 */
@Composable
private fun ModifierPresetsDialog(
    presets: List<ModifierPreset>,
    selectedPresetId: Long?,
    previews: ModifierPreviews?,
    onPick: (ModifierPreset) -> Unit,
    onDismiss: () -> Unit
) {
    val store = LocalModifierPresets.current
    var query by rememberSaveable { mutableStateOf("") }
    // Persist stable identities, not Room entities. Re-resolving from [presets] also means a row
    // removed elsewhere cannot leave a stale object behind an open confirmation.
    var renamingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }

    val shown = remember(presets, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) presets
        else presets.filter { it.name.contains(trimmed, ignoreCase = true) }
    }

    // The library stays on screen while a rename or a delete asks its question on top of it:
    // both are dialog windows, so they stack, and the row the question is about remains visible
    // behind it. Hiding the list instead made the two look like one dialog being replaced.
    RenkinAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.modifierPresetsTitle)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.modifierPresetsPickHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (presets.isEmpty()) {
                        Text(
                            text = stringResource(R.string.modifierPresetsEmpty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        SearchField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = stringResource(R.string.modifierPresetsSearch),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (shown.isEmpty()) {
                            Text(
                                text = stringResource(R.string.modifierPresetsNoMatch),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(shown, key = { it.id }) { preset ->
                                ModifierPresetRow(
                                    preset = preset,
                                    selected = preset.id == selectedPresetId,
                                    previews = previews,
                                    onClick = { onPick(preset) },
                                    onRename = { renamingId = preset.id },
                                    onDelete = { deletingId = preset.id }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
    )

    presets.firstOrNull { it.id == renamingId }?.let { preset ->
        RenameModifierPresetDialog(
            preset = preset,
            onRename = { newName ->
                store.rename(preset.id, newName)
                renamingId = null
            },
            onDismiss = { renamingId = null }
        )
    }

    presets.firstOrNull { it.id == deletingId }?.let { preset ->
        ConfirmDialog(
            title = stringResource(R.string.modifierPresetDeleteTitle),
            text = stringResource(R.string.modifierPresetDeleteText, preset.name),
            icon = Icons.Filled.Delete,
            onConfirm = {
                store.delete(preset.id)
                deletingId = null
            },
            onDismiss = { deletingId = null }
        )
    }
}

@Composable
private fun ModifierPresetRow(
    preset: ModifierPreset,
    selected: Boolean,
    previews: ModifierPreviews?,
    onClick: () -> Unit,
    onUpdate: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val payload = remember(preset.payload) { decodeModifierPreset(preset.payload) }
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        shape = InnerShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = payload?.let { presetGroupSummary(it) }
                        ?: stringResource(R.string.modifierPresetUnreadable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            PresetPreview(name = preset.name, payload = payload, previews = previews)
            if (onUpdate != null || onRename != null || onDelete != null) Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.moreOptions)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (onUpdate != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.modifierPresetUpdate)) },
                            leadingIcon = { Icon(Icons.Filled.BookmarkAdd, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onUpdate()
                            }
                        )
                    }
                    if (onRename != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.modifierPresetRename)) },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            }
                        )
                    }
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.modifierPresetDelete)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetPreview(
    name: String,
    payload: ModifierPresetPayload?,
    previews: ModifierPreviews?
) {
    val result by produceState(
        initialValue = PresetPreviewResult(),
        key1 = payload,
        key2 = previews?.presetKey
    ) {
        // Slider drags replace the options rapidly. Wait until the draft settles instead of
        // starting and cancelling five full icon renders for every pointer movement.
        delay(150)
        value = PresetPreviewResult(
            loaded = true,
            bitmap = runCatching { payload?.let { previews?.preset?.invoke(it) } }.getOrNull()
        )
    }
    Surface(
        shape = InnerShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .padding(start = 10.dp)
            .size(52.dp)
    ) {
        when {
            result.bitmap != null -> Image(
                bitmap = result.bitmap!!.asImageBitmap(),
                contentDescription = stringResource(R.string.modifierPresetPreview, name),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(4.dp)
                    .clip(InnerShape)
            )
            !result.loaded && payload != null && previews != null -> Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            else -> Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ImageNotSupported, contentDescription = null)
            }
        }
    }
}

private data class PresetPreviewResult(
    val loaded: Boolean = false,
    val bitmap: android.graphics.Bitmap? = null
)

@Composable
private fun RenameModifierPresetDialog(
    preset: ModifierPreset,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable(preset.id) { mutableStateOf(preset.name) }
    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modifierPresetRename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.modifierPresetName)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                shape = FieldShape,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.saveAction))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    )
}

/** "Shape · Colorize" — the groups a preset carries, in the order the tab shows them. */
@Composable
private fun presetGroupSummary(payload: ModifierPresetPayload): String {
    val parts = buildList {
        payload.effect?.let {
            add(getImageEditLabels(includeSegments = true)[it.imageEdit].orEmpty())
        }
        if (payload.iconScale != null) add(stringResource(R.string.iconScale))
        payload.shape?.let { add(stringResource(R.string.iconShapeTitle)) }
        payload.outline?.let { add(stringResource(R.string.outlineTitle)) }
    }.filter { it.isNotEmpty() }
    return parts.joinToString(" · ")
}

@Composable
private fun shapeSummary(adjustments: AdjustmentState): String =
    if (adjustments.iconShape == IconShape.NONE) {
        stringResource(R.string.shapeNone)
    } else {
        stringResource(
            R.string.modifierPresetShapeSummary,
            shapeLabel(adjustments.iconShape),
            (adjustments.shapeScale * 100).roundToInt()
        )
    }

@Composable
private fun outlineSummary(adjustments: AdjustmentState): String = stringResource(
    when (adjustments.outlineMode) {
        OutlineMode.NONE -> R.string.outlineNone
        OutlineMode.ADD -> R.string.outlineAdd
        OutlineMode.RECOLOR -> R.string.outlineRecolor
    }
)

/** Group selection survives a process death as the group names, joined by a separator. */
private fun modifierPresetGroupsSaver(): Saver<Set<ModifierPresetGroup>, String> = Saver(
    save = { groups -> groups.joinToString(",") { it.name } },
    restore = { saved ->
        saved.split(",")
            .mapNotNull { name -> ModifierPresetGroup.entries.firstOrNull { it.name == name } }
            .toSet()
    }
)
