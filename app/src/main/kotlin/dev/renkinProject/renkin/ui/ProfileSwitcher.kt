package dev.renkinProject.renkin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.HideProfileShareWarningKey
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.setBooleanValue
import dev.renkinProject.renkin.data.transfer.BackupManager
import kotlinx.coroutines.launch

// Input caps: the profile name doubles as the top-bar title and the pack label ends up as the
// launcher-visible app name of the built pack — unbounded text breaks both layouts.
private const val MAX_PROFILE_NAME = 24
private const val MAX_PROFILE_DESCRIPTION = 60
private const val MAX_PACK_LABEL = 30

/**
 * The top-bar title as a profile switcher: shows the active profile's name (the default profile
 * shows the app name) and opens a dropdown with every profile plus "Create new profile". Each
 * profile is its own icon set + preferences + built pack; the default one can't be deleted or
 * edited (its identity is the app itself), every other one gets edit + delete actions.
 */
@Composable
fun ProfileSwitcherTitle() {
    val viewModel: MainViewModel = hiltViewModel()
    val profiles by viewModel.profiles.collectAsState(initial = emptyList())
    val activeId = viewModel.activeProfileId
    val active = profiles.find { it.id == activeId }

    var menuOpen by remember { mutableStateOf(false) }
    var createOpen by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var pendingDelete by remember { mutableStateOf<Profile?>(null) }
    // Switch target (or create request) held while the save-before-switch prompt is up.
    var pendingSwitch by remember { mutableStateOf<Long?>(null) }
    var pendingCreate by remember { mutableStateOf<Triple<String, String, String>?>(null) }

    // Profile whose share was requested, held while the system file picker is up.
    var pendingShareId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Profile awaiting the pre-share warning's confirmation (null once past it).
    var shareWarningFor by remember { mutableStateOf<Profile?>(null) }
    val prefs = getPreferences()
    val hideShareWarning = prefs.getBooleanValue(HideProfileShareWarningKey)
    val scope = rememberCoroutineScope()
    val shareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val id = pendingShareId
        pendingShareId = null
        if (uri != null && id != null) viewModel.exportProfile(id, uri)
    }
    // Launches the file picker for [profile]'s share (warning already handled by the caller).
    val startShare: (Profile) -> Unit = { profile ->
        pendingShareId = profile.id
        shareLauncher.launch(BackupManager.profileFileName(profile.name))
    }
    // Shared-profile (or backup) file import, right where profiles are managed.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importFile(uri) }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { menuOpen = true }
        ) {
            // Single line + ellipsis: a long profile name must not wrap the whole top bar.
            Text(
                text = if (activeId == DEFAULT_PROFILE_ID) stringResource(R.string.app_name)
                    else active?.name ?: stringResource(R.string.app_name),
                // Expressive-style emphasis: only the weight is raised so the large app bar's
                // expanded/collapsed size animation keeps driving the rest of the style.
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.profilesTitle),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            profiles.forEach { profile ->
                val isActive = profile.id == activeId
                DropdownMenuItem(
                    // The active profile reads from the row itself (tinted background + bold
                    // name) instead of a leading check icon, which squeezed long names.
                    modifier = if (isActive) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier,
                    text = {
                        Column(Modifier.widthIn(max = 220.dp)) {
                            Text(
                                text = profile.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (isActive) FontWeight.Bold else null
                            )
                            if (profile.description.isNotEmpty()) {
                                Text(
                                    text = profile.description,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (profile.hasUnbuiltChanges) {
                                Text(
                                    text = stringResource(R.string.unbuiltChanges),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = {
                                menuOpen = false
                                // Warn (once) that the recipient needs the source packs, unless
                                // the user has opted out — then go straight to the file picker.
                                if (hideShareWarning) startShare(profile)
                                else shareWarningFor = profile
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Share,
                                    contentDescription = stringResource(R.string.shareProfile),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (profile.id != DEFAULT_PROFILE_ID) {
                                IconButton(onClick = {
                                    menuOpen = false
                                    editing = profile
                                }) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = stringResource(R.string.editProfile),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = {
                                    menuOpen = false
                                    pendingDelete = profile
                                }) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.deleteProfile),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    },
                    onClick = {
                        menuOpen = false
                        if (profile.id != activeId) {
                            // Unsaved work on the current profile? Offer to save it first.
                            if (viewModel.hasUnsavedChanges()) pendingSwitch = profile.id
                            else viewModel.switchProfile(profile.id)
                        }
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.createProfile)) },
                leadingIcon = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
                onClick = {
                    menuOpen = false
                    createOpen = true
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.importProfile)) },
                leadingIcon = { Icon(Icons.Filled.FileDownload, null, tint = MaterialTheme.colorScheme.primary) },
                onClick = {
                    menuOpen = false
                    importLauncher.launch(arrayOf("*/*"))
                }
            )
        }
    }

    if (createOpen) {
        ProfileDetailsDialog(
            title = stringResource(R.string.createProfileTitle),
            confirmLabel = stringResource(R.string.createProfileAction),
            onConfirm = { name, description, packLabel ->
                createOpen = false
                if (viewModel.hasUnsavedChanges()) pendingCreate = Triple(name, description, packLabel)
                else viewModel.createProfile(name, description, packLabel)
            },
            onDismiss = { createOpen = false }
        )
    }

    editing?.let { profile ->
        ProfileDetailsDialog(
            title = stringResource(R.string.editProfile),
            confirmLabel = stringResource(R.string.saveAction),
            initialName = profile.name,
            initialDescription = profile.description,
            initialPackLabel = profile.packLabel,
            onConfirm = { name, description, packLabel ->
                editing = null
                viewModel.updateProfileDetails(profile.id, name, description, packLabel)
            },
            onDismiss = { editing = null }
        )
    }

    // Save-before-switch prompt: Save keeps the current profile's icons without building
    // (marked "not built yet"), Don't save discards them; tapping outside cancels the switch.
    if (pendingSwitch != null || pendingCreate != null) {
        RenkinAlertDialog(
            onDismissRequest = { pendingSwitch = null; pendingCreate = null },
            icon = { Icon(Icons.Filled.Save, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.saveBeforeSwitchTitle)) },
            text = { Text(boldStringResource(R.string.saveBeforeSwitchText)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingSwitch?.let { viewModel.switchProfile(it, saveFirst = true) }
                    pendingCreate?.let { (n, d, l) -> viewModel.createProfile(n, d, l, saveFirst = true) }
                    pendingSwitch = null; pendingCreate = null
                }) { Text(stringResource(R.string.saveAction)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingSwitch?.let { viewModel.switchProfile(it, saveFirst = false) }
                    pendingCreate?.let { (n, d, l) -> viewModel.createProfile(n, d, l, saveFirst = false) }
                    pendingSwitch = null; pendingCreate = null
                }) { Text(stringResource(R.string.discardAction)) }
            }
        )
    }

    pendingDelete?.let { profile ->
        ConfirmDialog(
            title = stringResource(R.string.deleteProfile),
            text = stringResource(R.string.deleteProfileText, profile.name),
            icon = Icons.Filled.Delete,
            onConfirm = {
                pendingDelete = null
                viewModel.deleteProfile(profile.id)
            },
            onDismiss = { pendingDelete = null }
        )
    }

    shareWarningFor?.let { profile ->
        ProfileShareWarningDialog(
            onShare = { dontShowAgain ->
                shareWarningFor = null
                if (dontShowAgain) scope.launch { prefs.setBooleanValue(HideProfileShareWarningKey, true) }
                startShare(profile)
            },
            onDismiss = { shareWarningFor = null }
        )
    }
}

/**
 * Shared create/edit form: name + optional description + the launcher label of the profile's
 * built pack. Name and pack label are required (the label is what the launcher lists the pack
 * under — an empty one would install an unnamed pack); all fields are length-capped because
 * the name becomes the top-bar title and the label the pack's launcher name.
 */
@Composable
private fun ProfileDetailsDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (name: String, description: String, packLabel: String) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
    initialDescription: String = "",
    initialPackLabel: String = ""
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    var packLabel by rememberSaveable { mutableStateOf(initialPackLabel) }

    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_PROFILE_NAME) },
                    label = { Text(stringResource(R.string.profileName) + " *") },
                    supportingText = if (name.isBlank()) {
                        { Text(stringResource(R.string.requiredField)) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(MAX_PROFILE_DESCRIPTION) },
                    label = { Text(stringResource(R.string.profileDescription)) },
                    singleLine = true,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = packLabel,
                    onValueChange = { packLabel = it.take(MAX_PACK_LABEL) },
                    label = { Text(stringResource(R.string.profilePackLabel) + " *") },
                    supportingText = if (packLabel.isBlank()) {
                        { Text(stringResource(R.string.requiredField)) }
                    } else null,
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), description.trim(), packLabel.trim()) },
                enabled = name.isNotBlank() && packLabel.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    )
}
