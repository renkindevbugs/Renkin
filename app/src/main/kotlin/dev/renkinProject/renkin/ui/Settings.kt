@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.ui

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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DarkMode
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
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.data.DARK_MODE_DEFAULT
import dev.renkinProject.renkin.data.DarkModeKey
import dev.renkinProject.renkin.data.OnboardingSeenKey
import dev.renkinProject.renkin.data.setBooleanValue
import dev.renkinProject.renkin.data.getDarkModeLabels
import dev.renkinProject.renkin.data.getEnumValue
import dev.renkinProject.renkin.data.setEnumValue
import dev.renkinProject.renkin.data.transfer.BackupManager
import dev.renkinProject.renkin.util.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val scope = rememberCoroutineScope()

    var showStats by rememberSaveable { mutableStateOf(false) }
    var showCrashLogs by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var confirmClearIcons by rememberSaveable { mutableStateOf(false) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) viewModel.exportBackup(uri) }
    // Full backups stop at a confirmation (viewModel.pendingBackupImport); shared profiles
    // are additive and import right away.
    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importFile(uri) }

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
                Column(
                    Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    SettingsSectionHeader(stringResource(R.string.settingsAppearance))
                    ThemeRow(prefs)

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
                    SettingsSectionHeader(stringResource(R.string.settingsBackup))
                    SettingsRow(
                        Icons.Filled.Save,
                        stringResource(R.string.exportBackup),
                        busy = viewModel.backupInProgress
                    ) {
                        exportBackupLauncher.launch(BackupManager.defaultFileName())
                    }
                    SettingsRow(
                        Icons.Filled.Restore,
                        stringResource(R.string.importBackup),
                        busy = viewModel.backupInProgress
                    ) {
                        importBackupLauncher.launch(arrayOf("*/*"))
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
                        scope.launch { prefs.setBooleanValue(OnboardingSeenKey, false) }
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
                            Icons.Filled.Warning,
                            stringResource(R.string.forceCrash),
                            tint = MaterialTheme.colorScheme.error
                        ) {
                            throw RuntimeException("Forced crash for testing")
                        }
                    }

                    // Footer: version on the left, About opening the info dialog on the right.
                    HorizontalDivider(Modifier.padding(top = 16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.version, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showAbout = true }) {
                            Text(stringResource(R.string.aboutTitle))
                        }
                    }
                }
            }
        }
    }

    if (showStats) {
        PackUsageDialog(onDismiss = { showStats = false })
    }
    if (showCrashLogs) {
        CrashLogsScreen { showCrashLogs = false }
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
}

/** Muted section label above a group of settings rows. */
@Composable
private fun SettingsSectionHeader(text: String, color: Color = MaterialTheme.colorScheme.primary) {
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
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = (if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface)
                .copy(alpha = contentAlpha),
            modifier = Modifier.weight(1f)
        )
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

/** The theme picker row: current value on the right, options in a dropdown. */
@Composable
private fun ThemeRow(prefs: DataStore<Preferences>) {
    val scope = rememberCoroutineScope()
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
                    scope.launch { prefs.setEnumValue(DarkModeKey, mode) }
                }
            }
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
    val rows = entries.orEmpty()
    val max = (rows.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)

    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.packUsageTitle)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    entries == null -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    rows.isEmpty() -> Text(
                        text = stringResource(R.string.packUsageEmpty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                rows.forEach { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = entry.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (entry.count == 0) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurface
                                )
                                if (!entry.installed) {
                                    Text(
                                        text = " · " + stringResource(R.string.packUsageNotInstalled),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                            LinearProgressIndicator(
                                progress = { entry.count / max.toFloat() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 3.dp)
                            )
                        }
                        Text(
                            text = "${entry.count}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (entry.count == 0) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        }
    )
}
