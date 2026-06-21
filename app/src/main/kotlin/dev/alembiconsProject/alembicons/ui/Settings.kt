package dev.alembiconsProject.alembicons.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.alembiconsProject.alembicons.apk.ApkUninstaller
import dev.alembiconsProject.alembicons.apk.IconPackBuilder
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
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.outline,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column {
                DarkModeDropdown(prefs)
                SyncButton()
                RefreshApplicationListButton()
                RemoveIconsButton()
                DeleteIconPackButton()
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
            shape = RoundedCornerShape(16.dp),
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
        shape = RoundedCornerShape(16.dp),
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
        shape = RoundedCornerShape(16.dp),
        modifier = settingsButtonModifier
    ) {
        Text(stringResource(R.string.refreshApplicationList))
    }
}

@Composable
fun DeleteIconPackButton() {
    val context = getCurrentContext()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val view = LocalView.current

    // Destructive action — tonal/error styling sets it apart from the blue actions
    FilledTonalButton(
        onClick = {
            view.performConfirmHaptic()
            scope.launch {
                // Don't try (and then falsely report success) when the pack isn't installed
                val installed = runCatching {
                    context.packageManager.getPackageInfo(IconPackBuilder.PACKAGE_NAME, 0)
                }.isSuccess
                if (installed) {
                    if (ApkUninstaller(context).uninstall(IconPackBuilder.PACKAGE_NAME)) {
                        toaster.show(context.getString(R.string.iconPackUninstalled))
                    }
                } else {
                    toaster.show(context.getString(R.string.iconPackNotInstalled))
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
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
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ),
        modifier = settingsButtonModifier
    ) {
        Text(stringResource(R.string.clearIcons))
    }

    if (confirm) {
        AlertDialog(
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.clearIconsTitle)) },
            text = { Text(stringResource(R.string.clearIconsText)) },
            confirmButton = {
                TextButton(onClick = {
                    view.performConfirmHaptic()
                    confirm = false
                    viewModel.clearIcons()
                }) {
                    Text(
                        stringResource(R.string.confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        )
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