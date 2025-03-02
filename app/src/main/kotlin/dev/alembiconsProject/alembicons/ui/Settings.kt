package dev.alembiconsProject.alembicons.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.alembiconsProject.alembicons.BuildConfig
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.apk.ApkUninstaller
import dev.alembiconsProject.alembicons.data.AutomaticallyUpdateKey
import dev.alembiconsProject.alembicons.data.DARK_MODE_DEFAULT
import dev.alembiconsProject.alembicons.data.DarkMode
import dev.alembiconsProject.alembicons.data.DarkModeKey
import dev.alembiconsProject.alembicons.data.PackageAddedNotificationKey
import dev.alembiconsProject.alembicons.data.getBooleanValue
import dev.alembiconsProject.alembicons.data.getDarkModeLabels
import dev.alembiconsProject.alembicons.data.getEnumValue
import dev.alembiconsProject.alembicons.data.setBooleanValue
import dev.alembiconsProject.alembicons.data.setEnumValue
import dev.alembiconsProject.alembicons.packages.PermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsDialog(prefs: DataStore<Preferences>, onDismiss: (() -> Unit)) {
    var notificationPermissionWarning by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val activity = getCurrentMainActivity()
    val notification = prefs.getBooleanValue(PackageAddedNotificationKey)
    val automaticallyUpdate = prefs.getBooleanValue(AutomaticallyUpdateKey)

    AlertDialog(
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.outline,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column {
                DarkModeDropdown(prefs)
                PackageAddedNotificationSwitch(notification) {
                    if (it) {
                        val permissionManager = PermissionManager(activity)
                        if (!permissionManager.isPostNotificationEnabled()) {
                            permissionManager.askForPostNotification()
                        }

                        if (!permissionManager.isPostNotificationEnabled()) {
                            notificationPermissionWarning = true
                            return@PackageAddedNotificationSwitch
                        }

                        activity.startPackageAddedService()
                    } else {
                        activity.stopPackageAddedService()
                    }

                    scope.launch { prefs.setBooleanValue(PackageAddedNotificationKey, it) }
                }

                if (notification) {
                    AutomaticallyUpdateSwitch(automaticallyUpdate) {
                        scope.launch { prefs.setBooleanValue(AutomaticallyUpdateKey, it) }
                    }
                }

                SyncButton()
                RefreshApplicationListButton()
                RemoveIconsButton()
                DeleteIconPackButton()
                AppVersion()
            }
        },
        confirmButton = {}
    )

    if (notificationPermissionWarning) {
        ShowToast(stringResource(R.string.notifPermissionWarning))
        notificationPermissionWarning = false
    }
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
        modifier = Modifier.padding(8.dp, 4.dp)
    ) {
        TextField(
            readOnly = true,
            value = darkModeLabels[selectedOption]!!,
            onValueChange = { },
            label = { Text(stringResource(R.string.theme)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded
                )
            },
            colors = ExposedDropdownMenuDefaults.textFieldColors(),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
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

@Composable
fun SyncButton() {
    val mainActivity = getCurrentMainActivity()

    Button( onClick = {
        CoroutineScope(Dispatchers.Default).launch {
            mainActivity.appProvider.forceSync()
        }}
        , modifier = Modifier.padding(8.dp, 4.dp) ) {
        Text(stringResource(R.string.syncPacks))
    }
}

@Composable
fun RefreshApplicationListButton() {
    val mainActivity = getCurrentMainActivity()

    Button( onClick = { CoroutineScope(Dispatchers.Default).launch {
        mainActivity.appProvider.initialize()
    }}
        , modifier = Modifier.padding(8.dp, 4.dp) ) {
        Text(stringResource(R.string.refreshApplicationList))
    }
}

@Composable
fun DeleteIconPackButton() {
    val context = getCurrentContext()
    val scope = rememberCoroutineScope()

    var openSuccess by rememberSaveable { mutableStateOf(false) }

    Button( onClick = {
        scope.launch {
            openSuccess = ApkUninstaller(context).uninstall("com.kaanelloed.iconerationiconpack")
        }}
        , modifier = Modifier.padding(8.dp, 4.dp) ) {
        Text(stringResource(R.string.deleteIconPack))
    }

    if (openSuccess) {
        ShowToast(context.getString(R.string.iconPackUninstalled))
        openSuccess = false
    }
}

@Composable
fun RemoveIconsButton() {
    val mainActivity = getCurrentMainActivity()

    Button( onClick = { CoroutineScope(Dispatchers.Default).launch {
        mainActivity.appProvider.clearIcons()
    }}
        , modifier = Modifier.padding(8.dp, 4.dp) ) {
        Text(stringResource(R.string.clearIcons))
    }
}


@Composable
fun PackageAddedNotificationSwitch(notification: Boolean, onChange: (newValue: Boolean) -> Unit) {
    var checked by rememberSaveable { mutableStateOf(false) }

    checked = notification

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp, 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.packageAddedNotification))
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                onChange(it)
            },
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
fun AutomaticallyUpdateSwitch(automaticallyUpdate: Boolean, onChange: (newValue: Boolean) -> Unit) {
    var checked by rememberSaveable { mutableStateOf(false) }

    checked = automaticallyUpdate

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp, 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.automaticallyUpdate))
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                onChange(it)
            },
            modifier = Modifier.padding(start = 8.dp)
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