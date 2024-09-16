package com.kaanelloed.iconeration.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.kaanelloed.iconeration.R
import com.kaanelloed.iconeration.BuildConfig
import com.kaanelloed.iconeration.data.DarkMode
import com.kaanelloed.iconeration.data.getDarkModeLabels
import com.kaanelloed.iconeration.data.getDarkModeValue
import com.kaanelloed.iconeration.data.setDarkMode
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

    selectedOption = prefs.getDarkModeValue()
    val scope = rememberCoroutineScope()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            expanded = !expanded
        },
        modifier = Modifier.padding(8.dp)
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

                        scope.launch { prefs.setDarkMode(selectionOption.key) }
                    }
                )
            }
        }
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