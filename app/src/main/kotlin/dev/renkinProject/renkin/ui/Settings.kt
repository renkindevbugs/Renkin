package dev.renkinProject.renkin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import dev.renkinProject.renkin.data.IconPack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.BuildConfig
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.theme.FieldShape
import dev.renkinProject.renkin.data.DARK_MODE_DEFAULT
import dev.renkinProject.renkin.data.DarkModeKey
import dev.renkinProject.renkin.data.getDarkModeLabels
import dev.renkinProject.renkin.data.getEnumValue
import dev.renkinProject.renkin.data.setEnumValue
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsDialog(prefs: DataStore<Preferences>, onDismiss: (() -> Unit)) {
    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column {
                DarkModeDropdown(prefs)
                SyncButton()
                RefreshApplicationListButton()
                StatsButton()
                RemoveIconsButton()
                DeleteIconPackButton()
                CrashLogsButton()
                if (BuildConfig.DEBUG) {
                    ForceCrashButton()
                }
                AppVersion()
            }
        },
        confirmButton = {}
    )
}

@Composable
fun DarkModeDropdown(prefs: DataStore<Preferences>) {
    val scope = rememberCoroutineScope()
    val selected = prefs.getEnumValue(DarkModeKey, DARK_MODE_DEFAULT)
    EnumDropdown(
        labelId = R.string.theme,
        selected = selected,
        labels = getDarkModeLabels(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) { mode ->
        scope.launch { prefs.setEnumValue(DarkModeKey, mode) }
    }
}

// All settings actions share one full-width shape so the column lines up
private val settingsButtonModifier: Modifier
    @Composable get() = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 4.dp)

@Composable
fun SyncButton() {
    val viewModel: MainViewModel = hiltViewModel()

    Button(
        onClick = { viewModel.sync() },
        shape = FieldShape,
        modifier = settingsButtonModifier
    ) {
        Text(stringResource(R.string.syncPacks))
    }
}

@Composable
fun RefreshApplicationListButton() {
    val viewModel: MainViewModel = hiltViewModel()

    Button(
        onClick = { viewModel.refreshApps() },
        shape = FieldShape,
        modifier = settingsButtonModifier
    ) {
        Text(stringResource(R.string.refreshApplicationList))
    }
}

/** Opens the icon-pack usage stats modal. */
@Composable
fun StatsButton() {
    var showStats by rememberSaveable { mutableStateOf(false) }

    Button(
        onClick = { showStats = true },
        shape = FieldShape,
        modifier = settingsButtonModifier
    ) {
        Text(stringResource(R.string.statsButton))
    }

    if (showStats) {
        PackUsageDialog(onDismiss = { showStats = false })
    }
}

/**
 * Per-pack usage stats: how many stored icons were taken from each installed pack, so
 * heavy-lifter packs and never-used packs are both visible at a glance. Counts are read when
 * the modal opens, so they reflect the icons at the moment of looking.
 */
@Composable
private fun PackUsageDialog(onDismiss: () -> Unit) {
    val viewModel: MainViewModel = hiltViewModel()
    val packs = viewModel.iconPacks
    val usage = remember { viewModel.packUsageCounts() }
    val max = (usage.values.maxOrNull() ?: 0).coerceAtLeast(1)

    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.packUsageTitle)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (packs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.packUsageEmpty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                packs
                    .map { it to (usage[it.packageName] ?: 0) }
                    .sortedWith(compareByDescending<Pair<IconPack, Int>> { it.second }
                        .thenBy { it.first.applicationName.lowercase() })
                    .forEach { (pack, count) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = pack.applicationName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (count == 0) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurface
                                )
                                LinearProgressIndicator(
                                    progress = { count / max.toFloat() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 3.dp)
                                )
                            }
                            Text(
                                text = "$count",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (count == 0) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
            }
        }
    )
}

@Composable
fun DeleteIconPackButton() {
    val viewModel: MainViewModel = hiltViewModel()
    val view = LocalView.current

    // Destructive action — tonal/error styling sets it apart from the blue actions
    FilledTonalButton(
        onClick = {
            view.performConfirmHaptic()
            viewModel.deleteIconPack()
        },
        shape = FieldShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ),
        modifier = settingsButtonModifier
    ) {
        Text(stringResource(R.string.deleteIconPack))
    }
}

@Composable
fun RemoveIconsButton() {
    val viewModel: MainViewModel = hiltViewModel()
    val view = LocalView.current
    var confirm by rememberSaveable { mutableStateOf(false) }

    // Destructive action — tonal/error styling sets it apart from the blue actions
    FilledTonalButton(
        onClick = { confirm = true },
        shape = FieldShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ),
        modifier = settingsButtonModifier
    ) {
        Text(stringResource(R.string.clearIcons))
    }

    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.clearIconsTitle),
            text = stringResource(R.string.clearIconsText),
            onConfirm = {
                confirm = false
                viewModel.clearIcons()
            },
            onDismiss = { confirm = false }
        )
    }
}


@Composable
fun CrashLogsButton() {
    var open by rememberSaveable { mutableStateOf(false) }

    Button(
        onClick = { open = true },
        shape = FieldShape,
        modifier = settingsButtonModifier
    ) {
        Text(stringResource(R.string.crashLogs))
    }

    if (open) {
        CrashLogsScreen { open = false }
    }
}

/** Debug-only: throws to verify the crash-capture + report-on-next-launch flow. */
@Composable
private fun ForceCrashButton() {
    FilledTonalButton(
        onClick = { throw RuntimeException("Forced crash for testing") },
        shape = FieldShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ),
        modifier = settingsButtonModifier
    ) {
        Text(stringResource(R.string.forceCrash))
    }
}

@Composable
fun AppVersion() {
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp), thickness = Dp.Hairline, color = MaterialTheme.colorScheme.outline
    )
    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End) {
        Text(
            text = stringResource(R.string.version, BuildConfig.VERSION_NAME),
            fontSize = 12.sp
        )
    }
}