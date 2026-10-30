package dev.renkinProject.renkin.feature.profiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.DialogPrimaryAction
import dev.renkinProject.renkin.ui.DialogSecondaryAction
import dev.renkinProject.renkin.ui.MessageDialog
import dev.renkinProject.renkin.ui.OptionsDialog
import dev.renkinProject.renkin.ui.RenkinTooltipBox
import dev.renkinProject.renkin.ui.WavyLoadingBar
import dev.renkinProject.renkin.ui.theme.FieldShape
import dev.renkinProject.renkin.ui.theme.IconShape
import java.util.UUID

@Composable
internal fun ProfileDetailsDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (name: String, description: String, packLabel: String, customPackageName: String, packIcon: String) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
    initialDescription: String = "",
    initialPackLabel: String = "",
    initialCustomPackageName: String = "",
    initialPackIcon: String = "",
    automaticPackageName: String = ""
) {
    val main: MainViewModel = hiltViewModel()
    val state: ProfileDetailsViewModel = hiltViewModel()
    val formKey = rememberSaveable { UUID.randomUUID().toString() }
    val packs by main.iconPackState.collectAsStateWithLifecycle()
    val runtime by main.runtimeState.collectAsStateWithLifecycle()
    LaunchedEffect(formKey) {
        if (!state.owns(formKey)) {
            val defaultPackageName = automaticPackageName.ifEmpty { main.nextProfilePackPackageName().orEmpty() }
            state.start(formKey, initialName, initialDescription, initialPackLabel,
                initialCustomPackageName, initialPackIcon, defaultPackageName)
        }
    }
    val ready = state.owns(formKey)
    val blocked = !ready || state.busy || main.profileDetailsSaving
    val dismiss = {
        if (!blocked) {
            state.discard()
            onDismiss()
        }
    }
    val preview = state.preview

    MessageDialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(dismissOnBackPress = !blocked, dismissOnClickOutside = !blocked),
        badge = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(
                    painter = preview?.getPainter() ?: painterResource(R.drawable.xxxhdpi_ic_launcher),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(IconShape)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !blocked && packs.loaded,
                        onClick = { state.openEditor(runtime.sessionToken, packs.packs.firstOrNull()?.packageName.orEmpty()) }
                    ) { Text(stringResource(R.string.profileEditIcon)) }
                    if (state.hasCustomIcon) {
                        RenkinTooltipBox(stringResource(R.string.resetToDefault)) {
                            IconButton(enabled = !blocked, onClick = state::resetIcon) {
                                Icon(Icons.Filled.Restore, contentDescription = stringResource(R.string.resetToDefault))
                            }
                        }
                    }
                }
            }
        },
        title = title,
        actions = {
            DialogPrimaryAction(
                confirmLabel,
                onClick = {
                    val details = state.submission()
                    onConfirm(details.name, details.description, details.packLabel, details.customPackageName, details.packIcon)
                },
                enabled = !blocked && state.name.isNotBlank() && state.packLabel.isNotBlank()
            )
            DialogSecondaryAction(stringResource(R.string.dismiss), dismiss, enabled = !blocked)
        }
    ) {
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.profileIconHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.busy || main.profileDetailsSaving) WavyLoadingBar(Modifier.fillMaxWidth())
            state.errorRes?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = state.name,
                onValueChange = state::editName,
                label = { Text(stringResource(R.string.profileName) + " *") },
                supportingText = if (state.name.isBlank()) ({ Text(stringResource(R.string.requiredField)) }) else null,
                enabled = !blocked,
                singleLine = true,
                shape = FieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = state::editDescription,
                label = { Text(stringResource(R.string.profileDescription)) },
                enabled = !blocked,
                singleLine = true,
                shape = FieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.packLabel,
                onValueChange = state::editPackLabel,
                label = { Text(stringResource(R.string.profilePackLabel) + " *") },
                supportingText = {
                    Text(stringResource(if (state.packLabel.isBlank()) R.string.requiredField else R.string.profilePackLabelHint))
                },
                enabled = !blocked,
                singleLine = true,
                shape = FieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = state::toggleAdvanced, enabled = !blocked, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.advancedOptions))
                    Icon(if (state.advanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
                }
            }
            AnimatedVisibility(state.advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.customPackageName,
                        onValueChange = state::editPackageName,
                        label = { Text(stringResource(R.string.profilePackageName)) },
                        placeholder = { Text(automaticPackageName.ifEmpty { stringResource(R.string.profilePackageAutomatic) }) },
                        enabled = !blocked,
                        singleLine = true,
                        shape = FieldShape,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.hasCustomPackageName) {
                        TextButton(onClick = state::resetPackageName, enabled = !blocked) {
                            Text(stringResource(R.string.profilePackageReset))
                        }
                    }
                    Text(stringResource(R.string.profilePackageChangedHint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    state.editor?.let { editor ->
        OptionsDialog(
            iconPacks = packs.packs,
            session = editor,
            themed = false,
            onConfirm = { icon, _, _, _, sourcePack, sourceUrl, recipe, input ->
                state.confirmEditor(icon, sourcePack, sourceUrl, recipe, input)
            },
            onDismiss = state::closeEditor,
            onIconClear = { state.closeEditor(); state.resetIcon() },
            onRestoreModifiers = state::restoreModifiers,
            onCancelModifierRestore = state::cancelModifierRestore,
            dismissOnConfirm = false,
            canInteract = { !state.busy },
            autosaveNotice = {
                if (state.busy) WavyLoadingBar(Modifier.fillMaxWidth())
                state.errorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
        )
    }
}
