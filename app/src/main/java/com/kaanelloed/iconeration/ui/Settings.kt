package com.kaanelloed.iconeration.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.kaanelloed.iconeration.data.DarkMode
import com.kaanelloed.iconeration.data.DarkModeLabels
import com.kaanelloed.iconeration.data.getDarkMode
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
        title = { Text("Settings") },
        text = {
            DarkModeDropdown(prefs)
        },
        confirmButton = {}
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DarkModeDropdown(prefs: DataStore<Preferences>) {
    var expanded by remember { mutableStateOf(false) }
    var selectedOptionText by remember { mutableStateOf(DarkMode.FOLLOW_SYSTEM) }

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
            value = DarkModeLabels[prefs.getDarkModeValue()]!!,
            onValueChange = { },
            label = { Text("Theme") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded
                )
            },
            colors = ExposedDropdownMenuDefaults.textFieldColors(),
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            }
        ) {
            DarkModeLabels.forEach { selectionOption ->
                DropdownMenuItem(
                    text = { Text(text = selectionOption.value) },
                    onClick = {
                        selectedOptionText = selectionOption.key
                        expanded = false

                        scope.launch { prefs.setDarkMode(selectionOption.key) }
                    }
                )
            }
        }
    }
}