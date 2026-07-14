package dev.renkinProject.renkin.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.theme.FieldShape
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.data.getImageEditLabels
import dev.renkinProject.renkin.data.getSourceLabels
import dev.renkinProject.renkin.data.getTextTypeLabels
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.drawable.shrinkIfBiggerThan

@Composable
fun VectorSwitch(useVector: Boolean, onChange: (newValue: Boolean) -> Unit) {
    DefaultSwitchLayoutWithInfo(useVector, R.string.vector, R.string.vectorOptionDescription) { onChange(it) }
}

@Composable
fun MaterialYouSwitch(useMaterialYou: Boolean, onChange: (newValue: Boolean) -> Unit) {
    DefaultSwitchLayoutWithInfo(useMaterialYou, R.string.materialYou, R.string.materialYouOptionDescription) { onChange(it) }
}

@Composable
fun ThemedIconsSwitch(useThemed: Boolean, onChange: (newValue: Boolean) -> Unit) {
    DefaultSwitchLayoutWithInfo(useThemed, R.string.themedIcons, R.string.themedIconsOptionDescription) { onChange(it) }
}

@Composable
fun OutlineAddSwitch(outlineAdd: Boolean, onChange: (newValue: Boolean) -> Unit) {
    DefaultSwitchLayoutWithInfo(outlineAdd, R.string.outlineGlobal, R.string.outlineGlobalOptionDescription) { onChange(it) }
}

@Composable
fun SourceDropdown(@StringRes labelId: Int, source: Source, onChange: (newValue: Source) -> Unit) =
    EnumDropdown(labelId, source, getSourceLabels(), onChange = onChange)

@Composable
fun ImageEditDropdown(@StringRes labelId: Int, type: ImageEdit, onChange: (newValue: ImageEdit) -> Unit) =
    EnumDropdown(labelId, type, getImageEditLabels(), onChange = onChange)

@Composable
fun TextTypeDropdown(
    @StringRes labelId: Int,
    type: TextType,
    // Custom text only makes sense per app, so just the edit dialog opts in.
    includeCustom: Boolean = false,
    onChange: (newValue: TextType) -> Unit
) = EnumDropdown(labelId, type, getTextTypeLabels(includeCustom), onChange = onChange)

/**
 * The shared read-only anchor field for the exposed dropdowns: a labelled [OutlinedTextField]
 * with the dropdown trailing chevron, anchored to its menu box. Used by [EnumDropdown] and
 * [IconPackDropdown].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExposedDropdownMenuBoxScope.DropdownAnchorField(
    @StringRes labelId: Int,
    value: String,
    expanded: Boolean
) {
    OutlinedTextField(
        readOnly = true,
        value = value,
        onValueChange = { },
        label = { Text(stringResource(labelId)) },
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        shape = FieldShape,
        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        modifier = Modifier
            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            .fillMaxWidth()
    )
}

/**
 * A read-only outlined dropdown over a fixed set of [labels]. The option dropdowns (source /
 * image modifier / text type) and the settings theme picker only differ by their label map and
 * outer [modifier].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> EnumDropdown(
    @StringRes labelId: Int,
    selected: T,
    labels: Map<T, String>,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp),
    onChange: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        DropdownAnchorField(labelId, labels[selected] ?: "", expanded)
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            labels.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(text = label) },
                    onClick = {
                        expanded = false
                        onChange(key)
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
    val viewModel: MainViewModel = hiltViewModel()
    val emptyPack = IconPack("", stringResource(R.string.none), 0, "", 0)
    val newList = listOf(emptyPack) + iconPacks
    val defaultPack = newList.find { it.packageName == packageName }

    // remember (not rememberSaveable): ResourceDrawable holds a live Drawable that isn't
    // Parcelable, so saving it on stop crashes. It's reloaded below anyway.
    var icons: Map<String, ResourceDrawable> by remember { mutableStateOf(mapOf()) }
    var expanded by remember { mutableStateOf(false) }
    var selectedOption by remember { mutableStateOf(defaultPack ?: emptyPack) }

    LaunchedEffect(Unit) {
        icons = viewModel.iconPackDropdownIcons(application)
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            expanded = !expanded
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        DropdownAnchorField(labelId, selectedOption.applicationName, expanded)
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            }
        ) {
            newList.forEach { selectionOption ->
                val icon = icons.entries.find { it.key == selectionOption.packageName }
                val bitmap = icon?.value?.drawable?.shrinkIfBiggerThan(500)

                DropdownMenuItem(
                    modifier = Modifier.padding(vertical = 2.dp),
                    text = {
                        Text(
                            text = selectionOption.applicationName,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    },
                    leadingIcon = {
                        if (bitmap != null) {
                            Image(
                                painter = BitmapIconDrawable(bitmap).getPainter(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(9.dp))
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
    RenkinAlertDialog(
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                onChange(it)
            }
        )
    }
}

@Composable
fun DefaultSwitchLayoutWithInfo(isChecked: Boolean, @StringRes label: Int, @StringRes infoDesc: Int, onChange: (newValue: Boolean) -> Unit) {
    var checked by rememberSaveable { mutableStateOf(isChecked) }
    var openInfo by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        // Info sits left of the switch so all switches stay aligned to the edge
        IconButton(onClick = { openInfo = true }, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = stringResource(R.string.optionInfo),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                onChange(it)
            },
            modifier = Modifier.padding(start = 12.dp)
        )
    }

    if (openInfo) {
        OptionInfoDialog(stringResource(infoDesc)) {
            openInfo = false
        }
    }
}
