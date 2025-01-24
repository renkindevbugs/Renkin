package com.kaanelloed.iconeration.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.kaanelloed.iconeration.R
import com.kaanelloed.iconeration.data.BackgroundColorKey
import com.kaanelloed.iconeration.data.CalendarIconsKey
import com.kaanelloed.iconeration.data.ExportThemedKey
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.data.IMAGE_EDIT_DEFAULT
import com.kaanelloed.iconeration.data.IconColorKey
import com.kaanelloed.iconeration.data.IconPack
import com.kaanelloed.iconeration.data.ImageEdit
import com.kaanelloed.iconeration.data.IncludeVectorKey
import com.kaanelloed.iconeration.data.InstalledApplication
import com.kaanelloed.iconeration.data.MonochromeKey
import com.kaanelloed.iconeration.data.OverrideIconKey
import com.kaanelloed.iconeration.data.PrimaryIconPackKey
import com.kaanelloed.iconeration.data.PrimaryImageEditKey
import com.kaanelloed.iconeration.data.PrimarySourceKey
import com.kaanelloed.iconeration.data.PrimaryTextTypeKey
import com.kaanelloed.iconeration.data.SOURCE_DEFAULT
import com.kaanelloed.iconeration.data.SecondaryIconPackKey
import com.kaanelloed.iconeration.data.SecondaryImageEditKey
import com.kaanelloed.iconeration.data.SecondarySourceKey
import com.kaanelloed.iconeration.data.SecondaryTextTypeKey
import com.kaanelloed.iconeration.data.Source
import com.kaanelloed.iconeration.data.TEXT_TYPE_DEFAULT
import com.kaanelloed.iconeration.data.TextType
import com.kaanelloed.iconeration.data.getBooleanValue
import com.kaanelloed.iconeration.data.getColorValue
import com.kaanelloed.iconeration.data.getDefaultBackgroundColor
import com.kaanelloed.iconeration.data.getDefaultIconColor
import com.kaanelloed.iconeration.data.getEnumValue
import com.kaanelloed.iconeration.data.getImageEditLabels
import com.kaanelloed.iconeration.data.getSourceLabels
import com.kaanelloed.iconeration.data.getStringValue
import com.kaanelloed.iconeration.data.getTextTypeLabels
import com.kaanelloed.iconeration.data.setBooleanValue
import com.kaanelloed.iconeration.data.setColorValue
import com.kaanelloed.iconeration.data.setEnumValue
import com.kaanelloed.iconeration.data.setStringValue
import com.kaanelloed.iconeration.drawable.ResourceDrawable
import com.kaanelloed.iconeration.icon.BitmapIcon
import com.kaanelloed.iconeration.icon.ExportableIcon
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.packages.ApplicationManager
import com.kaanelloed.iconeration.packages.PackageVersion
import kotlinx.coroutines.launch

@Composable
fun AppOptions(
    iconPacks: List<IconPack>,
    app: PackageInfoStruct,
    onConfirm: (icon: ExportableIcon) -> Unit,
    onDismiss: () -> Unit,
    onIconClear: () -> Unit
) {
    OptionsDialog(iconPacks, app, onConfirm, onDismiss, onIconClear)
}

@Composable
fun OptionsCard(
    iconPacks: List<IconPack>
) {
    val prefs = getPreferences()

    var expanded by remember { mutableStateOf(false) }

    var primarySource by rememberSaveable { mutableStateOf(SOURCE_DEFAULT) }
    var primaryImageEdit by rememberSaveable { mutableStateOf(IMAGE_EDIT_DEFAULT) }
    var primaryTextType by rememberSaveable { mutableStateOf(TEXT_TYPE_DEFAULT) }
    var primaryIconPack by rememberSaveable { mutableStateOf("") }
    var secondarySource by rememberSaveable { mutableStateOf(SOURCE_DEFAULT) }
    var secondaryImageEdit by rememberSaveable { mutableStateOf(IMAGE_EDIT_DEFAULT) }
    var secondaryTextType by rememberSaveable { mutableStateOf(TEXT_TYPE_DEFAULT) }
    var secondaryIconPack by rememberSaveable { mutableStateOf("") }
    var useVector by rememberSaveable { mutableStateOf(false) }
    var useMonochrome by rememberSaveable { mutableStateOf(false) }
    var useThemed by rememberSaveable { mutableStateOf(false) }
    var retrieveCalendarIcons by rememberSaveable { mutableStateOf(false) }
    var overrideIcon by rememberSaveable { mutableStateOf(false) }

    val currentColor = prefs.getColorValue(IconColorKey, prefs.getDefaultIconColor())
    val currentBgColor = prefs.getColorValue(BackgroundColorKey, prefs.getDefaultBackgroundColor())

    primarySource = prefs.getEnumValue(PrimarySourceKey, SOURCE_DEFAULT)
    primaryImageEdit = prefs.getEnumValue(PrimaryImageEditKey, IMAGE_EDIT_DEFAULT)
    primaryTextType = prefs.getEnumValue(PrimaryTextTypeKey, TEXT_TYPE_DEFAULT)
    primaryIconPack = prefs.getStringValue(PrimaryIconPackKey)
    secondarySource = prefs.getEnumValue(SecondarySourceKey, SOURCE_DEFAULT)
    secondaryImageEdit = prefs.getEnumValue(SecondaryImageEditKey, IMAGE_EDIT_DEFAULT)
    secondaryTextType = prefs.getEnumValue(SecondaryTextTypeKey, TEXT_TYPE_DEFAULT)
    secondaryIconPack = prefs.getStringValue(SecondaryIconPackKey)
    useVector = prefs.getBooleanValue(IncludeVectorKey)
    useMonochrome = prefs.getBooleanValue(MonochromeKey)
    useThemed = prefs.getBooleanValue(ExportThemedKey)
    retrieveCalendarIcons = prefs.getBooleanValue(CalendarIconsKey)
    overrideIcon = prefs.getBooleanValue(OverrideIconKey)

    val pathTracing = isPathTracingEnabled(primarySource, primaryImageEdit, secondarySource, secondaryImageEdit)
    val showIconColor = showIconColor(primarySource, primaryImageEdit, secondarySource, secondaryImageEdit, useThemed)
    val showBgColor = showBackgroundColor(primarySource, primaryImageEdit, secondarySource, secondaryImageEdit, useThemed)

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
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
                SourceDropdown(R.string.primarySource, primarySource) { scope.launch { prefs.setEnumValue(PrimarySourceKey, it) } }

                if (needIconPack(primarySource)) {
                    IconPackDropdown(R.string.primaryIconPack, iconPacks, primaryIconPack, null) { scope.launch { prefs.setStringValue(
                        PrimaryIconPackKey, it.packageName) } }
                }

                if (needImageEdit(primarySource)) {
                    ImageEditDropdown(R.string.primaryImageEdit, primaryImageEdit) { scope.launch { prefs.setEnumValue(
                        PrimaryImageEditKey, it) } }
                }

                if (needTextType(primarySource)) {
                    TextTypeDropdown(R.string.primaryTextType, primaryTextType) { scope.launch { prefs.setEnumValue(
                        PrimaryTextTypeKey, it) } }
                }

                if (needSecondarySource(primarySource)) {
                    HorizontalDivider(modifier = Modifier.padding(8.dp, 0.dp)
                        , color = MaterialTheme.colorScheme.primary)

                    SourceDropdown(R.string.secondarySource, secondarySource) { scope.launch { prefs.setEnumValue(
                        SecondarySourceKey, it) } }

                    if (needIconPack(secondarySource)) {
                        IconPackDropdown(R.string.secondaryIconPack, iconPacks, secondaryIconPack, null) { scope.launch { prefs.setStringValue(
                            SecondaryIconPackKey, it.packageName) } }
                    }

                    if (needImageEdit(secondarySource)) {
                        ImageEditDropdown(R.string.secondaryImageEdit, secondaryImageEdit) { scope.launch { prefs.setEnumValue(
                            SecondaryImageEditKey, it) } }
                    }

                    if (needTextType(secondarySource)) {
                        TextTypeDropdown(R.string.secondaryTextType, secondaryTextType) { scope.launch { prefs.setEnumValue(
                            SecondaryTextTypeKey, it) } }
                    }
                }

                if (isIconPackSelected(primarySource, primaryIconPack)) {
                    RetrieveCalendarIconsSwitch(retrieveCalendarIcons) { scope.launch { prefs.setBooleanValue(
                        CalendarIconsKey, it) } }
                }

                if (showIconColor) {
                    ColorButton(stringResource(R.string.iconColor), currentColor) { scope.launch { prefs.setColorValue(
                        IconColorKey, it) } }
                }
                if (showBgColor) {
                    ColorButton(stringResource(R.string.backgroundColor), currentBgColor) { scope.launch { prefs.setColorValue(
                        BackgroundColorKey, it) } }
                }

                OverrideIconSwitch(overrideIcon) { scope.launch { prefs.setBooleanValue(
                    OverrideIconKey, it) } }

                if (pathTracing) {
                    VectorSwitch(useVector) { scope.launch { prefs.setBooleanValue(IncludeVectorKey, it) } }
                    MonochromeSwitch(useMonochrome) { scope.launch { prefs.setBooleanValue(
                        MonochromeKey, it) } }
                }

                ThemedIconsSwitch(useThemed) { scope.launch { prefs.setBooleanValue(ExportThemedKey, it) } }
            }
        }
    }
}

fun needImageEdit(source: Source): Boolean {
    return source == Source.ICON_PACK || source == Source.APPLICATION_ICON
}

fun needTextType(source: Source): Boolean {
    return source == Source.APPLICATION_NAME
}

fun needIconPack(source: Source): Boolean {
    return source == Source.ICON_PACK
}

fun needSecondarySource(source: Source): Boolean {
    return source == Source.ICON_PACK
}

fun isPathTracingEnabled(primarySource: Source, primaryImageEdit: ImageEdit, secondarySource: Source, secondaryImageEdit: ImageEdit): Boolean {
    if (primarySource == Source.ICON_PACK) {
        if (isPathTracingEnabled(secondarySource, secondaryImageEdit)) {
            return true
        }
    }

    return isPathTracingEnabled(primarySource, primaryImageEdit)
}

fun isIconPackSelected(source: Source, iconPack: String): Boolean {
    return source == Source.ICON_PACK && iconPack != ""
}

fun isPathTracingEnabled(source: Source, imageEdit: ImageEdit): Boolean {
    if (needImageEdit(source)) {
        return imageEdit == ImageEdit.PATH
    }

    return false
}

fun supportDynamicColors(): Boolean {
    return PackageVersion.is31OrMore()
}

fun showIconColor(primarySource: Source, primaryImageEdit: ImageEdit, secondarySource: Source, secondaryImageEdit: ImageEdit, themed: Boolean): Boolean {
    if (primarySource == Source.ICON_PACK) {
        if (!showIconColor(secondarySource, secondaryImageEdit, themed)) {
            return false
        }
    }

    return showIconColor(primarySource, primaryImageEdit, themed)
}

fun showIconColor(source: Source, imageEdit: ImageEdit, themed: Boolean): Boolean {
    if (needImageEdit(source) && imageEdit == ImageEdit.PATH && themed) {
        if (supportDynamicColors()) {
            return false
        }
    }

    return true
}

@Composable
fun showBackgroundColor(primarySource: Source, primaryImageEdit: ImageEdit, secondarySource: Source, secondaryImageEdit: ImageEdit, themed: Boolean): Boolean {
    if (primarySource == Source.ICON_PACK) {
        if (showBackgroundColor(secondarySource, secondaryImageEdit, themed)) {
            return true
        }
    }

    return showBackgroundColor(primarySource, primaryImageEdit, themed)
}

@Composable
fun showBackgroundColor(source: Source, imageEdit: ImageEdit, themed: Boolean): Boolean {
    if (needImageEdit(source) && imageEdit == ImageEdit.PATH && themed) {
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
fun SourceDropdown(@StringRes labelId: Int, source: Source, onChange: (newValue: Source) -> Unit) {
    val typeLabels = getSourceLabels()

    var expanded by remember { mutableStateOf(false) }
    var selectedOption by rememberSaveable { mutableStateOf(SOURCE_DEFAULT) }

    selectedOption = source

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
            label = { Text(stringResource(labelId)) },
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
fun ImageEditDropdown(@StringRes labelId: Int, type: ImageEdit, onChange: (newValue: ImageEdit) -> Unit) {
    val typeLabels = getImageEditLabels()

    var expanded by remember { mutableStateOf(false) }
    var selectedOption by rememberSaveable { mutableStateOf(IMAGE_EDIT_DEFAULT) }

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
            label = { Text(stringResource(labelId)) },
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
fun TextTypeDropdown(@StringRes labelId: Int, type: TextType, onChange: (newValue: TextType) -> Unit) {
    val typeLabels = getTextTypeLabels()

    var expanded by remember { mutableStateOf(false) }
    var selectedOption by rememberSaveable { mutableStateOf(TEXT_TYPE_DEFAULT) }

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
            label = { Text(stringResource(labelId)) },
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
    @StringRes labelId: Int,
    iconPacks: List<IconPack>,
    packageName: String,
    application: InstalledApplication?,
    onChange: (newValue: IconPack) -> Unit
) {
    val activity = getCurrentMainActivity()
    val emptyPack = IconPack("", stringResource(R.string.none), 0, "", 0)
    val newList = listOf(emptyPack) + iconPacks
    val defaultPack = newList.find { it.packageName == packageName }

    var icons: Map<String, ResourceDrawable> by rememberSaveable { mutableStateOf(mapOf()) }
    var expanded by remember { mutableStateOf(false) }
    var selectedOption by remember { mutableStateOf(defaultPack ?: emptyPack) }

    LaunchedEffect(Unit) {
        icons = activity.appProvider.getIconPackDropdownIcons(application)
    }

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
            label = { Text(stringResource(labelId)) },
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
                val icon = icons.entries.find { it.key == selectionOption.packageName }

                DropdownMenuItem(
                    text = { Text(text = selectionOption.applicationName) },
                    trailingIcon = {
                        if (icon != null) {
                            Image(
                                painter = BitmapIcon(icon.value.drawable.toBitmap()).getPainter(),
                                contentDescription = null,
                                modifier = Modifier.size(50.dp)
                            )
                        }
                    },
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