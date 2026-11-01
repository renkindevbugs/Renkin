package dev.renkinProject.renkin.feature.profiles

import dev.renkinProject.renkin.ui.DialogSplitActions
import dev.renkinProject.renkin.ui.DialogSecondaryAction
import dev.renkinProject.renkin.ui.DialogBadge
import dev.renkinProject.renkin.ui.MessageDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import dev.renkinProject.renkin.apk.automaticPackPackageName
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.ConfirmDialog
import dev.renkinProject.renkin.ui.IconPackStudioExportWarningDialog
import dev.renkinProject.renkin.ui.IconPackStudioWarningTarget
import dev.renkinProject.renkin.ui.ProfileShareWarningDialog
import dev.renkinProject.renkin.ui.boldStringResource
import dev.renkinProject.renkin.ui.getPreferences
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.HideProfileShareWarningKey
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.transfer.BackupManager
import kotlinx.coroutines.launch

/**
 * The top-bar title as a profile switcher: shows the active profile's name (the default profile
 * shows the app name) and opens a dropdown with every profile plus "Create new profile". Each
 * profile is its own icon set + preferences + built pack; the default one can't be deleted.
 */
@Composable
fun ProfileSwitcherTitle() {
    val viewModel: MainViewModel = hiltViewModel()
    val detailsViewModel: ProfileDetailsViewModel = hiltViewModel()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    val activeId = runtimeState.activeProfileId
    val active = profiles.find { it.id == activeId }

    var menuOpen by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Profile?>(null) }
    var createOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editing = profiles.find { it.id == editingId }
    var pendingDelete by remember { mutableStateOf<Profile?>(null) }
    // Switch target (or create request) held while the save-before-switch prompt is up.
    var pendingSwitch by rememberSaveable { mutableStateOf<Long?>(null) }
    // The retained form owns the submitted artwork while this prompt is open, never a Bundle.
    var pendingCreate by rememberSaveable { mutableStateOf(false) }
    // Unsaved icons die with the process, so a prompt restored into a new one asks about nothing.
    var pendingPromptProcess by rememberSaveable { mutableStateOf<String?>(null) }
    val currentProcess = runtimeState.sessionToken.providerInstanceId
    val savePromptRequested = pendingSwitch != null || pendingCreate
    val savePromptCurrent = pendingPromptProcess == currentProcess
    if (savePromptRequested && !savePromptCurrent) {
        LaunchedEffect(currentProcess) { pendingSwitch = null; pendingCreate = false }
    }

    // Profile whose share was requested, held while the system file picker is up.
    var pendingShareId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Profile awaiting the pre-share warning's confirmation (null once past it).
    var shareWarningFor by remember { mutableStateOf<Profile?>(null) }
    // IPS warnings cannot be disabled: different personal exports share one package identity.
    var ipsWarningFor by remember { mutableStateOf<Profile?>(null) }
    var shareCheckInProgress by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val prefs = getPreferences()
    val hideShareWarning = prefs.getBooleanValue(HideProfileShareWarningKey)
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
    val requestShare: (Profile) -> Unit = { profile ->
        scope.launch {
            shareCheckInProgress = true
            try {
                when {
                    viewModel.profileUsesIconPackStudio(profile.id) -> ipsWarningFor = profile
                    hideShareWarning -> startShare(profile)
                    else -> shareWarningFor = profile
                }
            } finally {
                shareCheckInProgress = false
            }
        }
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

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = {
                menuOpen = false
                actionsFor = null
            }
        ) {
            val actionProfile = actionsFor
            if (actionProfile != null) {
                ProfileActionsMenu(
                    profile = actionProfile,
                    shareEnabled = !shareCheckInProgress,
                    onBack = { actionsFor = null },
                    onShare = {
                        actionsFor = null
                        menuOpen = false
                        requestShare(actionProfile)
                    },
                    onEdit = {
                        actionsFor = null
                        menuOpen = false
                        editingId = actionProfile.id
                    },
                    onDelete = {
                        actionsFor = null
                        menuOpen = false
                        pendingDelete = actionProfile
                    }
                )
            } else {
                profiles.forEach { profile ->
                    val isActive = profile.id == activeId
                    DropdownMenuItem(
                        // The active profile reads from the row itself (tinted background + bold
                        // name) instead of a leading check icon, leaving the leading edge clean.
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
                            IconButton(onClick = { actionsFor = profile }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.moreOptions),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        onClick = {
                            menuOpen = false
                            actionsFor = null
                            if (profile.id != activeId) {
                                // Unsaved work on the current profile? Offer to save it first.
                                if (viewModel.hasUnsavedChanges()) {
                                    pendingPromptProcess = currentProcess
                                    pendingSwitch = profile.id
                                } else viewModel.switchProfile(profile.id)
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
    }

    if (createOpen) {
        ProfileDetailsDialog(
            title = stringResource(R.string.createProfileTitle),
            confirmLabel = stringResource(R.string.createProfileAction),
            onConfirm = { name, description, packLabel, customPackageName, packIcon ->
                // The save prompt stacks ON the form instead of replacing it: cancelling the
                // prompt then returns to the details the user already typed, rather than an
                // empty dialog they have to fill in again.
                if (viewModel.hasUnsavedChanges()) {
                    detailsViewModel.pendingSubmission = ProfileDetailsSubmission(
                        name, description, packLabel, customPackageName, packIcon
                    )
                    pendingPromptProcess = currentProcess
                    pendingCreate = true
                } else {
                    viewModel.createProfile(name, description, packLabel,
                        customPackageName = customPackageName, packIcon = packIcon)
                }
            },
            onDismiss = { createOpen = false }
        )
    }

    val createdDetailsId = viewModel.createdProfileDetailsId
    LaunchedEffect(createdDetailsId) {
        if (createdDetailsId == null) return@LaunchedEffect
        createOpen = false
        pendingCreate = false
        detailsViewModel.discard()
        viewModel.consumeCreatedProfileDetails()
    }

    val savedDetailsId = viewModel.savedProfileDetailsId
    LaunchedEffect(savedDetailsId) {
        if (savedDetailsId == null) return@LaunchedEffect
        if (editingId == savedDetailsId) {
            editingId = null
            detailsViewModel.discard()
        }
        viewModel.consumeSavedProfileDetails()
    }
    editing?.let { profile ->
        ProfileDetailsDialog(
            title = stringResource(R.string.editProfile),
            confirmLabel = stringResource(R.string.saveAction),
            initialName = profile.name,
            initialDescription = profile.description,
            initialPackLabel = profile.packLabel,
            initialCustomPackageName = profile.customPackageName,
            initialPackIcon = profile.packIcon,
            automaticPackageName = automaticPackPackageName(profile.id),
            onConfirm = { name, description, packLabel, customPackageName, packIcon ->
                viewModel.updateProfileDetails(profile.id, name, description, packLabel, customPackageName, packIcon)
            },
            onDismiss = { editingId = null }
        )
    }

    // Save-before-switch prompt: Save keeps the current profile's icons without building
    // (marked "not built yet"), Don't save discards them; tapping outside cancels the switch.
    if (savePromptRequested && savePromptCurrent) {
        SaveBeforeSwitchDialog(
            onSave = {
                // Saving an unchanged profile would still mark it "not built yet".
                val saveFirst = viewModel.hasUnsavedChanges()
                pendingSwitch?.let { viewModel.switchProfile(it, saveFirst = saveFirst) }
                if (pendingCreate) {
                    detailsViewModel.pendingSubmission?.let { details ->
                        viewModel.createProfile(details.name, details.description, details.packLabel,
                            saveFirst = saveFirst, customPackageName = details.customPackageName, packIcon = details.packIcon)
                    }
                }
                pendingSwitch = null; pendingCreate = false
            },
            onDiscard = {
                pendingSwitch?.let { viewModel.switchProfile(it, saveFirst = false) }
                if (pendingCreate) {
                    detailsViewModel.pendingSubmission?.let { details ->
                        viewModel.createProfile(details.name, details.description, details.packLabel,
                            saveFirst = false, customPackageName = details.customPackageName, packIcon = details.packIcon)
                    }
                }
                pendingSwitch = null; pendingCreate = false
            },
            onDismiss = { pendingSwitch = null; pendingCreate = false }
        )
    }

    pendingDelete?.let { profile ->
        ConfirmDialog(
            eyebrow = stringResource(R.string.deleteProfile),
            title = profile.name,
            text = stringResource(R.string.deleteProfileText, profile.name),
            confirmLabel = stringResource(R.string.deleteProfile),
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
                if (dontShowAgain) {
                    viewModel.hideProfileShareWarning()
                }
                startShare(profile)
            },
            onDismiss = { shareWarningFor = null }
        )
    }

    ipsWarningFor?.let { profile ->
        IconPackStudioExportWarningDialog(
            target = IconPackStudioWarningTarget.PROFILE,
            onContinue = {
                ipsWarningFor = null
                if (hideShareWarning) startShare(profile)
                else shareWarningFor = profile
            },
            onDismiss = { ipsWarningFor = null }
        )
    }
}

@Composable
internal fun ProfileActionsMenu(
    profile: Profile,
    shareEnabled: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = profile.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold
            )
        },
        leadingIcon = {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.backToProfiles)
            )
        },
        onClick = onBack
    )
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text(stringResource(R.string.shareProfile)) },
        leadingIcon = {
            Icon(Icons.Filled.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        enabled = shareEnabled,
        onClick = onShare
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.editProfile)) },
        leadingIcon = {
            Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        onClick = onEdit
    )
    if (profile.id != DEFAULT_PROFILE_ID) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.deleteProfile), color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            onClick = onDelete
        )
    }
}

@Composable
internal fun SaveBeforeSwitchDialog(onSave: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    MessageDialog(
        onDismissRequest = onDismiss,
        badge = { DialogBadge(Icons.Filled.Save) },
        title = stringResource(R.string.saveBeforeSwitchTitle),
        body = boldStringResource(R.string.saveBeforeSwitchText),
        actions = {
            DialogSplitActions(
                startText = stringResource(R.string.discardAction), onStart = onDiscard,
                endText = stringResource(R.string.saveAction), onEnd = onSave,
                startDanger = true
            )
            DialogSecondaryAction(stringResource(R.string.colorizeCancel), onDismiss)
        }
    )
}
