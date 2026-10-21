@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.BuildConfig
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.feature.buildinstall.InstallerPickerScreen
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.feature.debug.DialogGalleryScreen
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.installerSelection
import dev.renkinProject.renkin.data.DARK_MODE_DEFAULT
import dev.renkinProject.renkin.data.DarkMode
import dev.renkinProject.renkin.data.DarkModeKey
import dev.renkinProject.renkin.data.InstallMethod
import dev.renkinProject.renkin.data.AskInstallerEveryTimeKey
import dev.renkinProject.renkin.data.AUTO_BACKUP_INTERVAL_OFF
import dev.renkinProject.renkin.data.getDarkModeLabels
import dev.renkinProject.renkin.data.getEnumValue
import dev.renkinProject.renkin.data.getPreferencesValue
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.transfer.BackupManager
import dev.renkinProject.renkin.util.CrashReporter
import dev.renkinProject.renkin.feature.help.InfoDialog
import dev.renkinProject.renkin.feature.help.WhatsNewDialog
import dev.renkinProject.renkin.feature.help.WhatsNewScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Fullscreen settings screen (Mihon-style): a plain top bar with a back arrow and the options
 * as icon rows grouped under section headers — Appearance / Icon packs / Data / Diagnostics —
 * with the destructive actions tinted and separated. Replaces the old cramped AlertDialog.
 */
@Composable
fun SettingsScreen(prefs: DataStore<Preferences>, onDismiss: () -> Unit) {
    val viewModel: MainViewModel = hiltViewModel()
    val view = LocalView.current
    val context = getCurrentContext()

    var showStats by rememberSaveable { mutableStateOf(false) }
    var showCrashLogs by rememberSaveable { mutableStateOf(false) }
    var showWhatsNew by rememberSaveable { mutableStateOf(false) }
    var showStartupWhatsNewPreview by rememberSaveable { mutableStateOf(false) }
    var showDialogGallery by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var confirmClearIcons by rememberSaveable { mutableStateOf(false) }
    var showIpsBackupWarning by rememberSaveable { mutableStateOf(false) }
    var showInstallerPicker by rememberSaveable { mutableStateOf(false) }
    var backupCheckInProgress by remember { mutableStateOf(false) }
    var confirmRestorePreviousState by rememberSaveable { mutableStateOf(false) }
    val previousStateAvailable by viewModel.previousStateAvailable.collectAsStateWithLifecycle()
    val previousStateStored by viewModel.previousStateStored.collectAsStateWithLifecycle()
    var confirmDiscardPreviousState by rememberSaveable { mutableStateOf(false) }
    val abandonedRecoveryAvailable by viewModel.abandonedRecoveryAvailable.collectAsStateWithLifecycle()
    var confirmDiscardAbandonedRecovery by rememberSaveable { mutableStateOf(false) }
    var pendingAutoBackupInterval by rememberSaveable { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val installerSelection = prefs.getPreferencesValue().installerSelection()
    val askInstallerEveryTime = prefs.getBooleanValue(AskInstallerEveryTimeKey)
    val installerLabel by produceState<String?>(null, installerSelection) {
        value = viewModel.installerLabel(installerSelection)
    }
    val autoBackupInterval by viewModel.autoBackupIntervalHours.collectAsStateWithLifecycle()
    val autoBackupTreeUri by viewModel.autoBackupTreeUri.collectAsStateWithLifecycle()
    val lastAutoBackupAt by viewModel.lastAutoBackupAt.collectAsStateWithLifecycle()
    val autoBackupFolderName by produceState<String?>(null, autoBackupTreeUri) {
        value = autoBackupTreeUri?.let { viewModel.autoBackupFolderName(it) }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) viewModel.exportBackup(uri) }
    // Full backups stop at a confirmation (viewModel.pendingBackupImport); shared profiles
    // are additive and import right away.
    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importFile(uri) }
    val autoBackupFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val interval = pendingAutoBackupInterval
        pendingAutoBackupInterval = null
        if (uri != null && interval != null) viewModel.setAutoBackupFolder(uri, interval)
    }

    // Badge on the Crash logs row; reloaded when returning from the crash list (deletes there).
    val crashCount by produceState(0, showCrashLogs) {
        if (!showCrashLogs) value = withContext(Dispatchers.Default) { CrashReporter.list(context).size }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.settings)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            ) { innerPadding ->
                CenteredFullscreenContent(Modifier.padding(innerPadding)) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                    ) {
                    SettingsSectionHeader(stringResource(R.string.settingsAppearance))
                    ThemeRow(prefs, viewModel::setDarkMode)

                    SettingsSectionHeader(stringResource(R.string.settingsIconPacks))
                    SettingsRow(
                        Icons.Filled.Sync,
                        stringResource(R.string.syncPacks),
                        busy = viewModel.syncing
                    ) {
                        viewModel.sync()
                    }
                    SettingsRow(
                        Icons.Filled.Apps,
                        stringResource(R.string.refreshApplicationList),
                        busy = viewModel.appsRefreshing
                    ) {
                        viewModel.refreshApps()
                    }
                    SettingsRow(Icons.Filled.BarChart, stringResource(R.string.statsButton)) {
                        showStats = true
                    }

                    SettingsSectionHeader(stringResource(R.string.settingsInstallation))
                    InstallerMethodRow(
                        selectedLabel = installerLabel
                            ?: stringResource(R.string.installerUnavailable),
                        askEveryTime = askInstallerEveryTime,
                        onClick = { showInstallerPicker = true }
                    )

                    SettingsSectionHeader(stringResource(R.string.settingsBackup))
                    val autoBackupLabels = linkedMapOf(
                        0 to stringResource(R.string.autoBackupOff),
                        24 to stringResource(R.string.autoBackupDaily),
                        72 to stringResource(R.string.autoBackupEveryThreeDays),
                        168 to stringResource(R.string.autoBackupWeekly)
                    )
                    val lastBackupLabel = if (lastAutoBackupAt == 0L) {
                        stringResource(R.string.autoBackupNever)
                    } else {
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(lastAutoBackupAt))
                    }
                    AutoBackupRow(
                        selectedInterval = autoBackupInterval,
                        labels = autoBackupLabels,
                        lastBackupLabel = lastBackupLabel,
                        onSelect = { hours ->
                            if (hours == AUTO_BACKUP_INTERVAL_OFF || autoBackupTreeUri != null) {
                                viewModel.setAutoBackupInterval(hours)
                            } else {
                                pendingAutoBackupInterval = hours
                                autoBackupFolderLauncher.launch(null)
                            }
                        }
                    )
                    if (autoBackupInterval != AUTO_BACKUP_INTERVAL_OFF) {
                        SettingsRow(
                            Icons.Filled.Folder,
                            stringResource(R.string.autoBackupFolder),
                            subtitle = autoBackupFolderName
                                ?: stringResource(R.string.autoBackupChooseFolder)
                        ) {
                            pendingAutoBackupInterval = autoBackupInterval
                            autoBackupFolderLauncher.launch(autoBackupTreeUri)
                        }
                    }
                    SettingsRow(
                        Icons.Filled.Save,
                        stringResource(R.string.exportBackup),
                        busy = viewModel.backupInProgress || backupCheckInProgress
                    ) {
                        scope.launch {
                            backupCheckInProgress = true
                            try {
                                if (viewModel.backupUsesIconPackStudio()) {
                                    showIpsBackupWarning = true
                                } else {
                                    exportBackupLauncher.launch(BackupManager.defaultFileName())
                                }
                            } finally {
                                backupCheckInProgress = false
                            }
                        }
                    }
                    SettingsRow(
                        Icons.Filled.Restore,
                        stringResource(R.string.importBackup),
                        busy = viewModel.backupInProgress
                    ) {
                        importBackupLauncher.launch(arrayOf("*/*"))
                    }

                    if (previousStateAvailable) {
                        SettingsRow(
                            Icons.Filled.Restore,
                            stringResource(R.string.restorePreviousState),
                            busy = viewModel.backupInProgress
                        ) { confirmRestorePreviousState = true }
                    }
                    if (previousStateStored) {
                        SettingsRow(
                            Icons.Filled.Delete,
                            stringResource(R.string.discardPreviousState),
                            busy = viewModel.backupInProgress
                        ) { confirmDiscardPreviousState = true }
                    }
                    if (abandonedRecoveryAvailable) {
                        SettingsRow(
                            Icons.Filled.Delete,
                            stringResource(R.string.discardAbandonedRecovery),
                            busy = viewModel.backupInProgress
                        ) { confirmDiscardAbandonedRecovery = true }
                    }

                    SettingsSectionHeader(stringResource(R.string.settingsData), color = MaterialTheme.colorScheme.error)
                    SettingsRow(
                        Icons.Filled.DeleteSweep,
                        stringResource(R.string.clearIcons),
                        busy = viewModel.clearingIcons
                    ) {
                        confirmClearIcons = true
                    }
                    SettingsRow(
                        Icons.Filled.Delete,
                        stringResource(R.string.deleteIconPack),
                        tint = MaterialTheme.colorScheme.error
                    ) {
                        view.performConfirmHaptic()
                        viewModel.deleteIconPack()
                    }

                    SettingsSectionHeader(stringResource(R.string.settingsDiagnostics))
                    SettingsRow(Icons.Filled.School, stringResource(R.string.showIntro)) {
                        // Clearing the flag makes the home screen show the intro again; close
                        // Settings so it isn't sitting underneath the overlay.
                        viewModel.setOnboardingSeen(false)
                        onDismiss()
                    }
                    SettingsRow(
                        Icons.Filled.BugReport,
                        stringResource(R.string.crashLogs),
                        trailing = {
                            if (crashCount > 0) {
                                Badge { Text(crashCount.toString()) }
                            }
                        }
                    ) {
                        showCrashLogs = true
                    }
                    if (BuildConfig.DEBUG) {
                        SettingsRow(
                            Icons.Filled.NewReleases,
                            stringResource(R.string.previewStartupWhatsNew)
                        ) {
                            showStartupWhatsNewPreview = true
                        }
                        SettingsRow(
                            Icons.Filled.Dashboard,
                            stringResource(R.string.dialogGallery)
                        ) {
                            showDialogGallery = true
                        }
                        SettingsRow(
                            Icons.Filled.Warning,
                            stringResource(R.string.forceCrash),
                            tint = MaterialTheme.colorScheme.error
                        ) {
                            throw RuntimeException("Forced crash for testing")
                        }
                    }

                    SettingsSectionHeader(stringResource(R.string.aboutTitle))
                    SettingsRow(Icons.Filled.NewReleases, stringResource(R.string.whatsNewTitle)) {
                        showWhatsNew = true
                    }
                    SettingsRow(Icons.Filled.Info, stringResource(R.string.aboutTitle)) {
                        showAbout = true
                    }

                    // Footer: the app details live in the section above; keep the installed
                    // version visible without turning it into another action.
                    HorizontalDivider(Modifier.padding(top = 16.dp))
                    Text(
                        text = stringResource(R.string.version, dev.renkinProject.renkin.util.AppBuildInfo.displayVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                }
            }
        }
    }

    if (showInstallerPicker) {
        InstallerPickerScreen(
            selected = installerSelection,
            askEveryTime = askInstallerEveryTime,
            onSelect = { selection ->
                viewModel.setInstallMethod(selection.method, selection.externalComponent)
                showInstallerPicker = false
            },
            onAskEveryTimeChange = viewModel::setAskInstallerEveryTime,
            onDismiss = { showInstallerPicker = false }
        )
    }

    if (showIpsBackupWarning) {
        IconPackStudioExportWarningDialog(
            target = IconPackStudioWarningTarget.BACKUP,
            onContinue = {
                showIpsBackupWarning = false
                exportBackupLauncher.launch(BackupManager.defaultFileName())
            },
            onDismiss = { showIpsBackupWarning = false }
        )
    }

    if (showStats) {
        PackUsageDialog(onDismiss = { showStats = false })
    }
    if (showCrashLogs) {
        CrashLogsScreen { showCrashLogs = false }
    }
    if (showWhatsNew) {
        WhatsNewScreen { showWhatsNew = false }
    }
    if (BuildConfig.DEBUG && showDialogGallery) {
        DialogGalleryScreen { showDialogGallery = false }
    }
    if (BuildConfig.DEBUG && showStartupWhatsNewPreview) {
        WhatsNewDialog { showStartupWhatsNewPreview = false }
    }
    if (showAbout) {
        InfoDialog { showAbout = false }
    }
    // Note: the full-backup import confirmation dialog is hosted by MainColumn, so it also
    // covers imports started from the profile switcher.
    if (confirmClearIcons) {
        ConfirmDialog(
            title = stringResource(R.string.clearIconsTitle),
            text = stringResource(R.string.clearIconsText),
            confirmLabel = stringResource(R.string.clearIcons),
            icon = Icons.Filled.DeleteSweep,
            // The acknowledgement toast comes from the view model once the clear really
            // finished — the list change alone is easy to miss from Settings.
            onConfirm = {
                confirmClearIcons = false
                viewModel.clearIcons()
            },
            onDismiss = { confirmClearIcons = false }
        )
    }
    if (confirmRestorePreviousState) {
        ConfirmDialog(
            title = stringResource(R.string.restorePreviousState),
            text = stringResource(R.string.restorePreviousStateConfirmation),
            confirmLabel = stringResource(R.string.backupRestoreAction),
            icon = Icons.Filled.Restore,
            onConfirm = {
                confirmRestorePreviousState = false
                viewModel.restorePreviousState()
            },
            onDismiss = { confirmRestorePreviousState = false }
        )
    }
    if (confirmDiscardPreviousState) {
        ConfirmDialog(
            title = stringResource(R.string.discardPreviousState),
            text = stringResource(R.string.discardPreviousStateConfirmation),
            confirmLabel = stringResource(R.string.editorRecoveryDiscard),
            icon = Icons.Filled.Delete,
            onConfirm = {
                confirmDiscardPreviousState = false
                viewModel.discardPreviousState()
            },
            onDismiss = { confirmDiscardPreviousState = false }
        )
    }
    if (confirmDiscardAbandonedRecovery) {
        ConfirmDialog(
            title = stringResource(R.string.discardAbandonedRecovery),
            text = stringResource(R.string.discardAbandonedRecoveryConfirmation),
            confirmLabel = stringResource(R.string.editorRecoveryDiscard),
            icon = Icons.Filled.Delete,
            onConfirm = {
                confirmDiscardAbandonedRecovery = false
                viewModel.discardAbandonedRecovery()
            },
            onDismiss = { confirmDiscardAbandonedRecovery = false }
        )
    }
}

/** Muted section label above a group of settings rows. */
@Composable
internal fun SettingsSectionHeader(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

/**
 * One tappable settings row: leading icon, label, optional trailing content (e.g. a badge).
 *
 * [busy] is for the rows whose work takes a noticeable moment (syncing packs, reloading the app
 * list, backup import/export). They used to swallow the tap silently while running, so a slow
 * operation looked like a dead row — now the row dims and shows a spinner instead.
 */
// internal, not private: the busy behaviour has its own compose test.
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    busy: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    val contentAlpha = if (busy) 0.5f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint.copy(alpha = contentAlpha),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = (if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface)
                    .copy(alpha = contentAlpha)
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
                )
            }
        }
        if (busy) {
            LoadingIndicator(
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            trailing?.invoke()
        }
    }
}

@Composable
private fun AutoBackupRow(
    selectedInterval: Int,
    labels: Map<Int, String>,
    lastBackupLabel: String,
    onSelect: (Int) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingsRow(
            icon = Icons.Filled.Backup,
            label = stringResource(R.string.automaticBackups),
            subtitle = stringResource(
                R.string.autoBackupSummary,
                labels[selectedInterval] ?: labels.getValue(AUTO_BACKUP_INTERVAL_OFF),
                lastBackupLabel
            ),
            onClick = { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            labels.forEach { (hours, label) ->
                CheckableDropdownItem(label, checked = hours == selectedInterval) {
                    open = false
                    onSelect(hours)
                }
            }
        }
    }
}

/** The theme picker row: current value on the right, options in a dropdown. */
@Composable
private fun ThemeRow(prefs: DataStore<Preferences>, onSelect: (DarkMode) -> Unit) {
    val selected = prefs.getEnumValue(DarkModeKey, DARK_MODE_DEFAULT)
    val labels = getDarkModeLabels()
    var open by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.DarkMode,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.theme),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = labels[selected] ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            labels.forEach { (mode, label) ->
                CheckableDropdownItem(label, checked = mode == selected) {
                    open = false
                    onSelect(mode)
                }
            }
        }
    }
}

@Composable
private fun InstallerMethodRow(
    selectedLabel: String,
    askEveryTime: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.InstallMobile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.installerMethod),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (askEveryTime) {
                    stringResource(R.string.installerAskEveryTimeEnabled)
                } else {
                    selectedLabel
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // The subtitle already names the installer unless the picker takes over; then the
        // trailing label shows which option the picker will preselect.
        if (askEveryTime) {
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Per-pack usage stats by TRUE origin: icons taken through one of Renkin's own built packs
 * count for the pack they originally came from (the built pack carries provenance), and
 * packs that aren't installed here still appear — marked so. Counts include icons currently
 * locked behind a missing pack. Read when the modal opens.
 */
@Composable
private fun PackUsageDialog(onDismiss: () -> Unit) {
    val viewModel: MainViewModel = hiltViewModel()
    // Null while the read runs. An empty list would be indistinguishable from "no packs", so
    // opening the dialog used to flash "No icon packs installed" before the rows arrived.
    val entries by produceState<List<ApplicationProvider.PackUsage>?>(null) {
        value = viewModel.packUsageEntries()
    }
    PackUsageDialog(entries, onDismiss)
}

@Composable
internal fun PackUsageDialog(entries: List<ApplicationProvider.PackUsage>?, onDismiss: () -> Unit) {
    MessageDialog(
        onDismissRequest = onDismiss,
        badge = { if (entries == null) DialogProgressBadge() else DialogBadge(Icons.Filled.BarChart, DialogTone.Neutral) },
        title = stringResource(R.string.packUsageTitle),
        body = if (entries?.isEmpty() == true) AnnotatedString(stringResource(R.string.packUsageEmpty)) else null,
        actions = { DialogPrimaryAction(stringResource(R.string.ok), onDismiss) }
    ) {
        if (!entries.isNullOrEmpty()) {
            val max = entries.maxOf { it.count }.coerceAtLeast(1)
            DialogCard {
                entries.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DialogFactRow(
                            entry.label + if (entry.installed) "" else " · " + stringResource(R.string.packUsageNotInstalled),
                            "${entry.count}"
                        )
                        LinearProgressIndicator(progress = { entry.count / max.toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

