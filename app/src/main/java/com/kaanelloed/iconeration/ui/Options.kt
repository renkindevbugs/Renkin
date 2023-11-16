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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kaanelloed.iconeration.IconGenerator
import com.kaanelloed.iconeration.PackageInfoStruct
import com.kaanelloed.iconeration.data.GenerationType
import com.kaanelloed.iconeration.data.IconPack
import com.kaanelloed.iconeration.data.TypeLabels
import com.kaanelloed.iconeration.data.getBackgroundColorValue
import com.kaanelloed.iconeration.data.getExportThemedValue
import com.kaanelloed.iconeration.data.getIconColorValue
import com.kaanelloed.iconeration.data.getIncludeVectorValue
import com.kaanelloed.iconeration.data.getMonochromeValue
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

@Composable
fun AppOptions(iconPacks: List<IconPack>, app: PackageInfoStruct, onConfirmation: (() -> Unit), onDismiss: (() -> Unit)) {
    OptionsDialog(iconPacks, app, onConfirmation, onDismiss)
}

@Composable
fun OptionsDialog(iconPacks: List<IconPack>, app: PackageInfoStruct, onConfirmation: (() -> Unit), onDismiss: (() -> Unit)) {
    AlertDialog(
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.outline,
        onDismissRequest = onDismiss,
        title = { Text(app.appName) },
        text = {
            OptionColumn(iconPacks, app)
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirmation()
                }
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            Button(
                onClick = {
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Dismiss")
            }
        }
    )
}

@Composable
fun OptionColumn(iconPacks: List<IconPack>, app: PackageInfoStruct) {
    var genType by rememberSaveable { mutableStateOf(GenerationType.PATH) }
    var useVector by rememberSaveable { mutableStateOf(false) }
    var useMonochrome by rememberSaveable { mutableStateOf(false) }
    var useThemed by rememberSaveable { mutableStateOf(false) }
    var iconColor by rememberSaveable(saver = colorSaver()) { mutableStateOf(Color.White) }

    var upload by remember { mutableStateOf(false) }
    var imageUri by remember { mutableStateOf(Uri.EMPTY) }

    val fgColors = getColors(Color.White)
    val bgColors = getColors(Color.Black)

    uploadedImage = null
    generatingOptions = null

    Column {
        Text(
            text = "Options",
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
            IconPackDropdown(iconPacks) {  }
            ColorButton("Icon color", fgColors) { iconColor = it }

            if (genType == GenerationType.PATH) {
                VectorSwitch(useVector) { useVector = it }
                MonochromeSwitch(useMonochrome) { useMonochrome = it }
                ThemedIconsSwitch(useThemed) { useThemed = it }

                if (useThemed && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    ColorButton("Background color", bgColors) { }
                }
            }

            generatingType = genType
            generatingOptions = IconGenerator.GenerationOptions(android.graphics.Color.parseColor(iconColor.toHexString()), useMonochrome, useVector)
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

    val fgColors = getColors(currentColor)
    val bgColors = getColors(currentBgColor)
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
                text = "Options",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(8.dp)
            )
            if (expanded) {
                TypeDropdown(genType) { scope.launch { prefs.setType(it) } }
                IconPackDropdown(iconPacks) {  }
                ColorButton("Icon color", fgColors) { scope.launch { prefs.setIconColor(it) } }

                if (genType == GenerationType.PATH) {
                    VectorSwitch(useVector) { scope.launch { prefs.setIncludeVector(it) } }
                    MonochromeSwitch(useMonochrome) { scope.launch { prefs.setMonochrome(it) } }
                    ThemedIconsSwitch(useThemed) { scope.launch { prefs.setExportThemed(it) } }

                    if (useThemed && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        ColorButton("Background color", bgColors) { scope.launch { prefs.setBackgroundColor(it) } }
                    }
                }
            }
        }
    }
}

@Composable
fun VectorSwitch(useVector: Boolean, onChange: ((newValue: Boolean) -> Unit)) {
    var checked by rememberSaveable { mutableStateOf(false) }

    checked = useVector

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Vector (BETA)")
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
fun MonochromeSwitch(useMonochrome: Boolean, onChange: ((newValue: Boolean) -> Unit)) {
    var checked by rememberSaveable { mutableStateOf(false) }

    checked = useMonochrome

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Monochrome (BETA)")
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
fun ThemedIconsSwitch(useThemed: Boolean, onChange: ((newValue: Boolean) -> Unit)) {
    var checked by rememberSaveable { mutableStateOf(false) }

    checked = useThemed

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Themed Icons (BETA)")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypeDropdown(type: GenerationType, onChange: ((newValue: GenerationType) -> Unit)) {
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

                        onChange(selectionOption.key)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconPackDropdown(iconPacks: List<IconPack>, onChange: ((newValue: IconPack) -> Unit)) {
    val emptyPack = IconPack("", "None", 0, "", 0)

    var expanded by remember { mutableStateOf(false) }
    var selectedOption by remember { mutableStateOf(emptyPack) }

    val newList = listOf(emptyPack) + iconPacks

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
        Text("Create")
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                onChange(it)
            },
            modifier = Modifier.padding(start = 8.dp, end = 8.dp)
        )
        Text("Upload")
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
        Text("Upload image")
    }
}

fun getColors(currentColor: Color): List<Color> {
    val baseColors = mutableListOf(
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

    return baseColors.toList()
}