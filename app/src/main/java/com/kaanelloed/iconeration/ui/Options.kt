package com.kaanelloed.iconeration.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import coil.compose.rememberAsyncImagePainter
import com.kaanelloed.iconeration.IconGenerator
import com.kaanelloed.iconeration.PackageInfoStruct
import com.kaanelloed.iconeration.data.GenerationType
import com.kaanelloed.iconeration.data.TypeLabels
import com.kaanelloed.iconeration.data.getBackgroundColorValue
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
fun AppOptions(app: PackageInfoStruct, onDismiss: (() -> Unit)) {
    OptionsDialog(app, onDismiss = onDismiss)
}

@Composable
fun OptionsDialog(app: PackageInfoStruct, onDismiss: (() -> Unit)) {
    AlertDialog(
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.outline,
        onDismissRequest = onDismiss,
        title = { Text(app.appName) },
        text = {
            OptionColumn(app)
        },
        confirmButton = {

        }
    )
}

@Composable
fun OptionColumn(app: PackageInfoStruct) {
    var genType by rememberSaveable { mutableStateOf(GenerationType.PATH) }
    var useVector by rememberSaveable { mutableStateOf(false) }
    var useMonochrome by rememberSaveable { mutableStateOf(false) }
    var useThemed by rememberSaveable { mutableStateOf(false) }

    var upload by remember { mutableStateOf(false) }
    var imageUri by remember { mutableStateOf(Uri.EMPTY) }

    val fgColors = getColors(Color.White)
    val bgColors = getColors(Color.Black)

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
                val contentResolver = getCurrentContext().contentResolver

                Image(painter = rememberAsyncImagePainter(imageUri), contentDescription = null)

                Button(onClick = {
                    val bp = contentResolver.openInputStream(imageUri).use { BitmapFactory.decodeStream(it) }
                    app.genIcon = bp
                }) {
                    Text("Confirm")
                }
            }
        } else {
            TypeDropdown(genType) { genType = it }
            IconPackDropdown()
            ColorButton("Icon color", fgColors) { }

            if (genType == GenerationType.PATH) {
                VectorSwitch(useVector) { useVector = it }
                MonochromeSwitch(useMonochrome) { useMonochrome = it }
                ThemedIconsSwitch(useThemed) { useThemed = it }

                if (useThemed) {
                    ColorButton("Background color", bgColors) { }
                }
            }

            val ctx = getCurrentContext()
            Button(onClick = {
                val opt = IconGenerator.GenerationOptions(android.graphics.Color.parseColor(Color.White.toHexString()), useMonochrome, useVector)
                IconGenerator(ctx, opt).generateIcons(app, genType)
            }) {
                Text("Confirm")
            }
        }
    }
}

@Composable
fun OptionsCard() {
    val prefs = getPreferences()

    var expanded by remember { mutableStateOf(false) }
    var genType by rememberSaveable { mutableStateOf(GenerationType.PATH) }
    var useVector by rememberSaveable { mutableStateOf(false) }
    var useMonochrome by rememberSaveable { mutableStateOf(false) }
    var useThemed by rememberSaveable { mutableStateOf(false) }

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
                TypeDropdown(genType) { scope.launch { prefs.setType(it) } }
                IconPackDropdown()
                ColorButton("Icon color", fgColors) { scope.launch { prefs.setIconColor(it) } }

                if (genType == GenerationType.PATH) {
                    VectorSwitch(useVector) { scope.launch { prefs.setIncludeVector(it) } }
                    MonochromeSwitch(useMonochrome) { scope.launch { prefs.setMonochrome(it) } }
                    ThemedIconsSwitch(useThemed) { scope.launch { prefs.setExportThemed(it) } }

                    if (useThemed) {
                        ColorButton("Background color", bgColors) {  }
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
        Text("Vector")
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
        Text("Monochrome")
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
        Text("Themed Icons")
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