package com.kaanelloed.iconeration.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kaanelloed.iconeration.R
import com.kaanelloed.iconeration.icon.creator.IconGenerator
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.data.GenerationType
import com.kaanelloed.iconeration.data.IconPack
import com.kaanelloed.iconeration.data.getBackgroundColorValue
import com.kaanelloed.iconeration.data.getExportThemedValue
import com.kaanelloed.iconeration.data.getIconColorValue
import com.kaanelloed.iconeration.data.getIncludeVectorValue
import com.kaanelloed.iconeration.data.getMonochromeValue
import com.kaanelloed.iconeration.data.getTypeLabels
import com.kaanelloed.iconeration.data.getTypeValue
import com.kaanelloed.iconeration.data.setBackgroundColor
import com.kaanelloed.iconeration.data.setExportThemed
import com.kaanelloed.iconeration.data.setIconColor
import com.kaanelloed.iconeration.data.setIncludeVector
import com.kaanelloed.iconeration.data.setMonochrome
import com.kaanelloed.iconeration.data.setType
import kotlinx.coroutines.launch

var uploadedImage: Bitmap? = null
var generatingOptions: IconGenerator.GenerationOptions? = null
var generatingType = GenerationType.PATH
var iconPackageName = ""

@Composable
fun AppOptions(iconPacks: List<IconPack>, app: PackageInfoStruct, onConfirmation: (() -> Unit), onDismiss: (() -> Unit), onIconClear: (() -> Unit)) {
    OptionsDialog(iconPacks, app, onConfirmation, onDismiss, onIconClear)
}

@Composable
fun OptionsDialog(iconPacks: List<IconPack>, app: PackageInfoStruct, onConfirmation: (() -> Unit), onDismiss: (() -> Unit), onIconClear: (() -> Unit)) {
    AlertDialog(
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.outline,
        onDismissRequest = onDismiss,
        title = { Text(app.appName) },
        text = {
            OptionColumn(iconPacks, onIconClear)
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirmation()
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            Button(
                onClick = {
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.dismiss))
            }
        }
    )
}

@Composable
fun OptionColumn(iconPacks: List<IconPack>, onIconClear: (() -> Unit)) {
    var genType by rememberSaveable { mutableStateOf(GenerationType.PATH) }
    var useVector by rememberSaveable { mutableStateOf(false) }
    var useMonochrome by rememberSaveable { mutableStateOf(false) }
    var iconColor by rememberSaveable(saver = colorSaver()) { mutableStateOf(Color.White) }

    var iconPack by rememberSaveable { mutableStateOf("") }

    var upload by remember { mutableStateOf(false) }
    var confirmClearIcon by remember { mutableStateOf(false) }
    var imageUri by remember { mutableStateOf(Uri.EMPTY) }

    uploadedImage = null
    generatingOptions = null
    iconPackageName = ""

    Column {
        Text(
            text = stringResource(id = R.string.options), 
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(8.dp)
        )

        UploadSwitch { upload = it }

        if (upload) {
            UploadButton { imageUri = it }
            if (imageUri != Uri.EMPTY) {
                AsyncImage(imageUri, contentDescription = null)

                val contentResolver = getCurrentContext().contentResolver
                uploadedImage = contentResolver.openInputStream(imageUri).use { BitmapFactory.decodeStream(it) }
            }
        } else {
            TypeDropdown(genType) { genType = it }
            IconPackDropdown(iconPacks, iconPack) { iconPack = it.packageName }
            ColorButton(stringResource(R.string.iconColor), Color.White) { iconColor = it }

            if (genType == GenerationType.PATH) {
                VectorSwitch(useVector) { useVector = it }
                MonochromeSwitch(useMonochrome) { useMonochrome = it }
            }

            iconPackageName = iconPack
            generatingType = genType
            generatingOptions = IconGenerator.GenerationOptions(android.graphics.Color.parseColor(iconColor.toHexString()), useMonochrome, useVector)
        }

        IconButton(onClick = {
            confirmClearIcon = true
        }) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Clear",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        if (confirmClearIcon) {
            AlertDialog(
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.outline,
                onDismissRequest = { confirmClearIcon = false },
                title = { Text(stringResource(R.string.confirmClear)) },
                text = {
                    Text(stringResource(R.string.confirmClearText))
                },
                confirmButton = {
                    Button(
                        onClick = {
                            confirmClearIcon = false
                            onIconClear()
                        }
                    ) {
                        Text(stringResource(R.string.confirm))
                    }
                },
                dismissButton = {
                    Button(
                        onClick = {
                            confirmClearIcon = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.dismiss))
                    }
                }
            )
        }
    }
}

@Composable
fun OptionsCard(iconPacks: List<IconPack>) {
    val prefs = getPreferences()

    var expanded by remember { mutableStateOf(false) }
    var genType by rememberSaveable { mutableStateOf(GenerationType.PATH) }
    var useVector by rememberSaveable { mutableStateOf(false) }
    var useMonochrome by rememberSaveable { mutableStateOf(false) }
    var useThemed by rememberSaveable { mutableStateOf(false) }

    var iconPack by rememberSaveable { mutableStateOf("") }

    val currentColor = prefs.getIconColorValue()
    val currentBgColor = prefs.getBackgroundColorValue()
    genType = prefs.getTypeValue()
    useVector = prefs.getIncludeVectorValue()
    useMonochrome = prefs.getMonochromeValue()
    useThemed = prefs.getExportThemedValue()

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
            Text(
                text = stringResource(id = R.string.options),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(8.dp)
            )
            if (expanded) {
                TypeDropdown(genType) { scope.launch { prefs.setType(it) } }
                IconPackDropdown(iconPacks, iconPack) { iconPack = it.packageName }

                if (showIconColor(genType, useThemed)) {
                    ColorButton(stringResource(R.string.iconColor), currentColor) { scope.launch { prefs.setIconColor(it) } }
                }
                if (showBackgroundColor(genType, useThemed)) {
                    ColorButton(stringResource(R.string.backgroundColor), currentBgColor) { scope.launch { prefs.setBackgroundColor(it) } }
                }

                if (genType == GenerationType.PATH) {
                    VectorSwitch(useVector) { scope.launch { prefs.setIncludeVector(it) } }
                    MonochromeSwitch(useMonochrome) { scope.launch { prefs.setMonochrome(it) } }
                    ThemedIconsSwitch(useThemed) { scope.launch { prefs.setExportThemed(it) } }
                }

                iconPackageName = iconPack
            }
        }
    }
}

@Composable
fun supportDynamicColors(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
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
fun VectorSwitch(useVector: Boolean, onChange: ((newValue: Boolean) -> Unit)) {
    var checked by rememberSaveable { mutableStateOf(false) }
    var openInfo by rememberSaveable { mutableStateOf(false) }

    checked = useVector

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.vector))
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
        AlertDialog(
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.outline,
            onDismissRequest = { openInfo = false },
            title = { },
            text = {
                Text(stringResource(R.string.vectorOptionDescription))
            },
            confirmButton = { },
            dismissButton = { }
        )
    }
}

@Composable
fun MonochromeSwitch(useMonochrome: Boolean, onChange: ((newValue: Boolean) -> Unit)) {
    var checked by rememberSaveable { mutableStateOf(false) }
    var openInfo by rememberSaveable { mutableStateOf(false) }

    checked = useMonochrome

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.monochrome))
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
        AlertDialog(
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.outline,
            onDismissRequest = { openInfo = false },
            title = { },
            text = {
                Text(stringResource(R.string.monochromeOptionDescription))
            },
            confirmButton = { },
            dismissButton = { }
        )
    }
}

@Composable
fun ThemedIconsSwitch(useThemed: Boolean, onChange: ((newValue: Boolean) -> Unit)) {
    var checked by rememberSaveable { mutableStateOf(false) }
    var openInfo by rememberSaveable { mutableStateOf(false) }

    checked = useThemed

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.themedIcons))
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
        AlertDialog(
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.outline,
            onDismissRequest = { openInfo = false },
            title = { },
            text = {
                Text(stringResource(R.string.themedIconsOptionDescription))
            },
            confirmButton = { },
            dismissButton = { }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypeDropdown(type: GenerationType, onChange: ((newValue: GenerationType) -> Unit)) {
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
            modifier = Modifier.menuAnchor()
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
fun IconPackDropdown(iconPacks: List<IconPack>, packageName: String, onChange: ((newValue: IconPack) -> Unit)) {
    val emptyPack = IconPack("", stringResource(R.string.none), 0, "", 0)
    val newList = listOf(emptyPack) + iconPacks

    var expanded by remember { mutableStateOf(false) }
    var selectedOption by remember { mutableStateOf(newList.find { it.packageName == packageName }!!) }

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
            modifier = Modifier.menuAnchor()
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
fun UploadSwitch(onChange: ((newValue: Boolean) -> Unit)) {
    var checked by rememberSaveable { mutableStateOf(false) }

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.create))
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                onChange(it)
            },
            modifier = Modifier.padding(start = 8.dp, end = 8.dp)
        )
        Text(stringResource(R.string.upload))
    }
}

@Composable
fun UploadButton(onChange: ((newValue: Uri) -> Unit)) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { imageUri ->
        if (imageUri != null) {
            onChange(imageUri)
        }
    }

    Button(onClick = {
        launcher.launch("image/*")
    }) {
        Text(stringResource(R.string.uploadImage))
    }
}