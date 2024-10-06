package com.kaanelloed.iconeration.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.kaanelloed.iconeration.R
import com.kaanelloed.iconeration.data.BackgroundColorKey
import com.kaanelloed.iconeration.data.CalendarIconsKey
import com.kaanelloed.iconeration.data.ColorizeIconPackKey
import com.kaanelloed.iconeration.data.ExportThemedKey
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.data.GenerationType
import com.kaanelloed.iconeration.data.IconColorKey
import com.kaanelloed.iconeration.data.IconPack
import com.kaanelloed.iconeration.data.IconPackKey
import com.kaanelloed.iconeration.data.IncludeVectorKey
import com.kaanelloed.iconeration.data.MonochromeKey
import com.kaanelloed.iconeration.data.OverrideIconKey
import com.kaanelloed.iconeration.data.TYPE_DEFAULT
import com.kaanelloed.iconeration.data.TypeKey
import com.kaanelloed.iconeration.data.getBooleanValue
import com.kaanelloed.iconeration.data.getColorValue
import com.kaanelloed.iconeration.data.getDefaultBackgroundColor
import com.kaanelloed.iconeration.data.getDefaultIconColor
import com.kaanelloed.iconeration.data.getEnumValue
import com.kaanelloed.iconeration.data.getStringValue
import com.kaanelloed.iconeration.data.getTypeLabels
import com.kaanelloed.iconeration.data.setBooleanValue
import com.kaanelloed.iconeration.data.setColorValue
import com.kaanelloed.iconeration.data.setEnumValue
import com.kaanelloed.iconeration.data.setStringValue
import com.kaanelloed.iconeration.packages.PackageVersion
import kotlinx.coroutines.launch

@Composable
fun AppOptions(
    iconPacks: List<IconPack>,
    app: PackageInfoStruct,
    onConfirmation: (options: IndividualOptions) -> Unit,
    onDismiss: () -> Unit,
    onIconClear: () -> Unit
) {
    OptionsDialog(iconPacks, app, onConfirmation, onDismiss, onIconClear)
}

@Composable
fun OptionsCard(
    iconPacks: List<IconPack>
) {
    val prefs = getPreferences()

    var expanded by remember { mutableStateOf(false) }
    var genType by rememberSaveable { mutableStateOf(GenerationType.PATH) }
    var useVector by rememberSaveable { mutableStateOf(false) }
    var useMonochrome by rememberSaveable { mutableStateOf(false) }
    var useThemed by rememberSaveable { mutableStateOf(false) }
    var colorizeIconPack by rememberSaveable { mutableStateOf(false) }
    var iconPack by rememberSaveable { mutableStateOf("") }
    var retrieveCalenderIcons by rememberSaveable { mutableStateOf(false) }
    var overrideIcon by rememberSaveable { mutableStateOf(false) }

    val currentColor = prefs.getColorValue(IconColorKey, prefs.getDefaultIconColor())
    val currentBgColor = prefs.getColorValue(BackgroundColorKey, prefs.getDefaultBackgroundColor())
    genType = prefs.getEnumValue(TypeKey, TYPE_DEFAULT)
    useVector = prefs.getBooleanValue(IncludeVectorKey)
    useMonochrome = prefs.getBooleanValue(MonochromeKey)
    useThemed = prefs.getBooleanValue(ExportThemedKey)
    colorizeIconPack = prefs.getBooleanValue(ColorizeIconPackKey)
    iconPack = prefs.getStringValue(IconPackKey)
    retrieveCalenderIcons = prefs.getBooleanValue(CalendarIconsKey)
    overrideIcon = prefs.getBooleanValue(OverrideIconKey)

    val scope = rememberCoroutineScope()

    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
            .clickable(
                onClick = { expanded = !expanded }
            )
    ) {
        Column {
            Row(modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.options),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(8.dp)
                )

                val optionIcon = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown
                Icon(
                    imageVector = optionIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            if (expanded) {
                TypeDropdown(genType) { scope.launch { prefs.setEnumValue(TypeKey, it) } }
                IconPackDropdown(iconPacks, iconPack) { scope.launch { prefs.setStringValue(
                    IconPackKey, it.packageName) } }

                if (iconPack != "") {
                    ColorizeIconPackSwitch(colorizeIconPack) { scope.launch { prefs.setBooleanValue(
                        ColorizeIconPackKey, it) } }
                    RetrieveCalendarIconsSwitch(retrieveCalenderIcons) { scope.launch { prefs.setBooleanValue(
                        CalendarIconsKey, it) } }
                }

                if (showIconColor(genType, useThemed)) {
                    ColorButton(stringResource(R.string.iconColor), currentColor) { scope.launch { prefs.setColorValue(
                        IconColorKey, it) } }
                }
                if (showBackgroundColor(genType, useThemed)) {
                    ColorButton(stringResource(R.string.backgroundColor), currentBgColor) { scope.launch { prefs.setColorValue(
                        BackgroundColorKey, it) } }
                }

                OverrideIconSwitch(overrideIcon) { scope.launch { prefs.setBooleanValue(
                    OverrideIconKey, it) } }

                if (genType == GenerationType.PATH) {
                    VectorSwitch(useVector) { scope.launch { prefs.setBooleanValue(IncludeVectorKey, it) } }
                    MonochromeSwitch(useMonochrome) { scope.launch { prefs.setBooleanValue(
                        MonochromeKey, it) } }
                }

                ThemedIconsSwitch(useThemed) { scope.launch { prefs.setBooleanValue(ExportThemedKey, it) } }
            }
        }
    }
}

@Composable
fun supportDynamicColors(): Boolean {
    return PackageVersion.is31OrMore()
}

@Composable
fun showIconColor(generationType: GenerationType, themed: Boolean): Boolean {
    if (generationType == GenerationType.PATH && themed) {
        if (supportDynamicColors()) {
            return false
        }
    }
    return true
}

@Composable
fun showBackgroundColor(generationType: GenerationType, themed: Boolean): Boolean {
    if (generationType == GenerationType.PATH && themed) {
        if (!supportDynamicColors()) {
            return true
        }
    }
    return false
}

@Composable
fun VectorSwitch(useVector: Boolean, onChange: (newValue: Boolean) -> Unit) {
    DefaultSwitchLayoutWithInfo(useVector, R.string.vector, R.string.vectorOptionDescription) { onChange(it) }
}

@Composable
fun MonochromeSwitch(useMonochrome: Boolean, onChange: (newValue: Boolean) -> Unit) {
    DefaultSwitchLayoutWithInfo(useMonochrome, R.string.monochrome, R.string.monochromeOptionDescription) { onChange(it) }
}

@Composable
fun ThemedIconsSwitch(useThemed: Boolean, onChange: (newValue: Boolean) -> Unit) {
    DefaultSwitchLayoutWithInfo(useThemed, R.string.themedIcons, R.string.themedIconsOptionDescription) { onChange(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypeDropdown(type: GenerationType, onChange: (newValue: GenerationType) -> Unit) {
    val typeLabels = getTypeLabels()

    var expanded by remember { mutableStateOf(false) }
    var selectedOption by rememberSaveable { mutableStateOf(GenerationType.PATH) }

    selectedOption = type

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            expanded = !expanded
        },
        modifier = Modifier.padding(8.dp)
    ) {
        TextField(
            readOnly = true,
            value = typeLabels[selectedOption]!!,
            onValueChange = { },
            label = { Text(stringResource(R.string.type)) },
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
            typeLabels.forEach { selectionOption ->
                DropdownMenuItem(
                    text = { Text(text = selectionOption.value) },
                    onClick = {
                        selectedOption = selectionOption.key
                        expanded = false

                        onChange(selectionOption.key)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconPackDropdown(
    iconPacks: List<IconPack>,
    packageName: String,
    onChange: (newValue: IconPack) -> Unit
) {
    val emptyPack = IconPack("", stringResource(R.string.none), 0, "", 0)
    val newList = listOf(emptyPack) + iconPacks
    val defaultPack = newList.find { it.packageName == packageName }

    var expanded by remember { mutableStateOf(false) }
    var selectedOption by remember { mutableStateOf(defaultPack ?: emptyPack) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            expanded = !expanded
        },
        modifier = Modifier.padding(8.dp)
    ) {
        TextField(
            readOnly = true,
            value = selectedOption.applicationName,
            onValueChange = { },
            label = { Text(stringResource(R.string.iconPack)) },
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
            newList.forEach { selectionOption ->
                DropdownMenuItem(
                    text = { Text(text = selectionOption.applicationName) },
                    onClick = {
                        selectedOption = selectionOption
                        expanded = false

                        onChange(selectionOption)
                    }
                )
            }
        }
    }
}

@Composable
fun OptionInfoDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.outline,
        onDismissRequest = { onDismiss() },
        title = { },
        text = {
            Text(text)
        },
        confirmButton = { },
        dismissButton = { }
    )
}

@Composable
fun ColorizeIconPackSwitch(colorize: Boolean, onChange: (newValue: Boolean) -> Unit) {
    DefaultSwitchLayout(colorize, R.string.colorizeIconPack) { onChange(it) }
}

@Composable
fun RetrieveCalendarIconsSwitch(retrieve: Boolean, onChange: (newValue: Boolean) -> Unit) {
    DefaultSwitchLayout(retrieve, R.string.retrieveCalendarIcon) { onChange(it) }
}

@Composable
fun OverrideIconSwitch(override: Boolean, onChange: (newValue: Boolean) -> Unit) {
    DefaultSwitchLayout(override, R.string.overrideIcon) { onChange(it) }
}

@Composable
fun DefaultSwitchLayout(isChecked: Boolean, @StringRes label: Int, onChange: (newValue: Boolean) -> Unit) {
    var checked by rememberSaveable { mutableStateOf(isChecked) }

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label))
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
fun DefaultSwitchLayoutWithInfo(isChecked: Boolean, @StringRes label: Int, @StringRes infoDesc: Int, onChange: (newValue: Boolean) -> Unit) {
    var checked by rememberSaveable { mutableStateOf(isChecked) }
    var openInfo by rememberSaveable { mutableStateOf(false) }

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label))
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                onChange(it)
            },
            modifier = Modifier.padding(start = 8.dp)
        )

        IconButton(onClick = { openInfo = true }) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = "Option info",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (openInfo) {
        OptionInfoDialog(stringResource(infoDesc)) {
            openInfo = false
        }
    }
}