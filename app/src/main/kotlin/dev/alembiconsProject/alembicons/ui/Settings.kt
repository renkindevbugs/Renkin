package dev.alembiconsProject.alembicons.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.alembiconsProject.alembicons.BuildConfig
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.ui.theme.DialogShape
import dev.alembiconsProject.alembicons.ui.theme.FieldShape
import dev.alembiconsProject.alembicons.data.DARK_MODE_DEFAULT
import dev.alembiconsProject.alembicons.data.DarkMode
import dev.alembiconsProject.alembicons.data.DarkModeKey
import dev.alembiconsProject.alembicons.data.getDarkModeLabels
import dev.alembiconsProject.alembicons.data.getEnumValue
import dev.alembiconsProject.alembicons.data.setEnumValue
import androidx.hilt.navigation.compose.hiltViewModel
import dev.alembiconsProject.alembicons.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsDialog(prefs: DataStore<Preferences>, onDismiss: (() -> Unit)) {
    AlertDialog(
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column {
                DarkModeDropdown(prefs)
                SyncButton()
                RefreshApplicationListButton()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DarkModeDropdown(prefs: DataStore<Preferences>) {
    val darkModeLabels = getDarkModeLabels()
    var expanded by remember { mutableStateOf(false) }
    var selectedOption by remember { mutableStateOf(DarkMode.FOLLOW_SYSTEM) }

    selectedOption = prefs.getEnumValue(DarkModeKey, DARK_MODE_DEFAULT)
    val scope = rememberCoroutineScope()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            expanded = !expanded
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        OutlinedTextField(
            readOnly = true,
            value = darkModeLabels[selectedOption]!!,
            onValueChange = { },
            label = { Text(stringResource(R.string.theme)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded
                )
            },
            shape = FieldShape,
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            }
        ) {
            darkModeLabels.forEach { selectionOption ->
                DropdownMenuItem(
                    text = { Text(text = selectionOption.value) },
                    onClick = {
                        selectedOption = selectionOption.key
                        expanded = false

                        scope.launch { prefs.setEnumValue(DarkModeKey, selectionOption.key) }
                    }
                )
            }
        }
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
            text = String.format(stringResource(R.string.version), BuildConfig.VERSION_NAME),
            fontSize = 12.sp
        )
    }
}