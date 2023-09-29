package com.kaanelloed.iconeration.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.kaanelloed.iconeration.data.GenerationType
import com.kaanelloed.iconeration.data.TypeLabels
import com.kaanelloed.iconeration.data.getExportThemedValue
import com.kaanelloed.iconeration.data.getIconColorValue
import com.kaanelloed.iconeration.data.getIncludeVectorValue
import com.kaanelloed.iconeration.data.getMonochromeValue
import com.kaanelloed.iconeration.data.getTypeValue
import com.kaanelloed.iconeration.data.setExportThemed
import com.kaanelloed.iconeration.data.setIconColor
import com.kaanelloed.iconeration.data.setIncludeVector
import com.kaanelloed.iconeration.data.setMonochrome
import com.kaanelloed.iconeration.data.setType
import kotlinx.coroutines.launch

@Composable
fun AppOptions(app: String, onDismiss: (() -> Unit)) {
    OptionsDialog(app, onDismiss = onDismiss)
}

@Composable
fun OptionsDialog(app: String, onDismiss: (() -> Unit)) {
    val colors = listOf(
        Color(0xFFFFFFFF),
        Color(0xFF000000),
        Color(0xFF80CBC4),
        Color(0xFFA5D6A7),
        Color(0xFFFFCC80),
        Color(0xFFFFAB91),
        Color(0xFF81D4FA),
        Color(0xFFCE93D8),
        Color(0xFFB39DDB)
    )

    AlertDialog(
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.outline,
        onDismissRequest = onDismiss,
        title = { Text(app) },
        text = {
            Text(app)
            ColorButton(colors = colors, onColorSelected = {})
        },
        confirmButton = {}
    )
}

@Composable
fun OptionsCard(prefs: DataStore<Preferences>) {
    var expanded by remember { mutableStateOf(false) }
    val currentColor = prefs.getIconColorValue()

    var baseColors = mutableListOf(
        Color(0xFFFFFFFF),
        Color(0xFF000000),
        Color(0xFF80CBC4),
        Color(0xFFA5D6A7),
        Color(0xFFFFCC80),
        Color(0xFFFFAB91),
        Color(0xFF81D4FA),
        Color(0xFFCE93D8),
        Color(0xFFB39DDB)
    )

    if (baseColors.contains(currentColor)) {
        baseColors.remove(currentColor)
        baseColors.add(0, currentColor)
    } else {
        baseColors.removeLast()
        baseColors.add(0, currentColor)
    }

    val colors = baseColors.toList()

    val scope = rememberCoroutineScope()

    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clickable(
                onClick = { expanded = !expanded }
            )
    ) {
        Column {
            Text(
                text = "Options",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(8.dp)
            )
            if (expanded) {
                val genType = prefs.getTypeValue()

                TypeDropdown(prefs, genType)
                IconPackDropdown()
                ColorButton(colors, onColorSelected = { scope.launch { prefs.setIconColor(it) } })

                if (genType == GenerationType.PATH) {
                    VectorSwitch(prefs)
                    MonochromeSwitch(prefs)
                    ThemedIconsSwitch(prefs)
                }
            }
        }
    }
}

@Composable
fun VectorSwitch(prefs: DataStore<Preferences>) {
    var checked by remember { mutableStateOf(false) }

    checked = prefs.getIncludeVectorValue()
    val scope = rememberCoroutineScope()

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Vector")
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                scope.launch { prefs.setIncludeVector(it) }
            },
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
fun MonochromeSwitch(prefs: DataStore<Preferences>) {
    var checked by remember { mutableStateOf(false) }

    checked = prefs.getMonochromeValue()
    val scope = rememberCoroutineScope()

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Monochrome")
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                scope.launch { prefs.setMonochrome(it) }
            },
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
fun ThemedIconsSwitch(prefs: DataStore<Preferences>) {
    var checked by remember { mutableStateOf(false) }

    checked = prefs.getExportThemedValue()
    val scope = rememberCoroutineScope()

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Themed Icons")
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                scope.launch { prefs.setExportThemed(it) }
            },
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypeDropdown(prefs: DataStore<Preferences>, type: GenerationType) {
    var expanded by remember { mutableStateOf(false) }
    var selectedOption by remember { mutableStateOf(GenerationType.PATH) }

    selectedOption = type
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
            value = TypeLabels[selectedOption]!!,
            onValueChange = { },
            label = { Text("Type") },
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
            TypeLabels.forEach { selectionOption ->
                DropdownMenuItem(
                    text = { Text(text = selectionOption.value) },
                    onClick = {
                        selectedOption = selectionOption.key
                        expanded = false

                        scope.launch { prefs.setType(selectionOption.key) }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconPackDropdown() {
    val options = listOf("1", "2", "3")
    var expanded by remember { mutableStateOf(false) }
    var selectedOptionText by remember { mutableStateOf(options[0]) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            expanded = !expanded
        },
        modifier = Modifier.padding(8.dp)
    ) {
        TextField(
            readOnly = true,
            value = selectedOptionText,
            onValueChange = { },
            label = { Text("Icon Pack") },
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
            options.forEach { selectionOption ->
                DropdownMenuItem(
                    text = { Text(text = selectionOption) },
                    onClick = {
                        selectedOptionText = selectionOption
                        expanded = false
                    }
                )
            }
        }
    }
}