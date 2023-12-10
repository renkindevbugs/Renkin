package com.kaanelloed.iconeration.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kaanelloed.iconeration.R
import com.kaanelloed.iconeration.icon.creator.IconGenerator
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.data.GenerationType
import com.kaanelloed.iconeration.data.IconPack
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.vector.EmptyVector.Companion.createEmptyVector
import com.kaanelloed.iconeration.vector.MutableImageVector
import com.kaanelloed.iconeration.vector.MutableImageVector.Companion.toMutableImageVector
import com.kaanelloed.iconeration.vector.MutableVectorPath
import com.kaanelloed.iconeration.vector.PathExporter.Companion.toStringPath
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.applyAndRemoveGroup
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.center

@Composable
fun OptionsDialog(
    iconPacks: List<IconPack>,
    app: PackageInfoStruct,
    onConfirmation: (options: IndividualOptions) -> Unit,
    onDismiss: () -> Unit,
    onIconClear: () -> Unit
) {
    var options: IndividualOptions = EmptyOptions()

    AlertDialog(
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.outline,
        onDismissRequest = onDismiss,
        title = { DialogTitle(app = app, onIconClear) },
        text = {
            TabOptions(iconPacks, app) {
                options = it
            }
        },
        confirmButton = {
            IconButton(onClick = {
                onConfirmation(options)
            }) {
                Icon(
                    imageVector = Icons.Filled.Done,
                    contentDescription = stringResource(R.string.confirm),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            IconButton(onClick = {
                onDismiss()
            }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.dismiss),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

@Composable
fun DialogTitle(app: PackageInfoStruct, onIconClear: () -> Unit) {
    var confirmClearIcon by remember { mutableStateOf(false) }

    Row(
        Modifier.height(30.dp)
        , verticalAlignment = Alignment.CenterVertically
    ) {
        Text(app.appName)
        IconButton(onClick = {
            confirmClearIcon = true
        }) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Clear",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (confirmClearIcon) {
        ConfirmClearDialog(onDismiss = { confirmClearIcon = false }, onIconClear)
    }
}

@Composable
fun ConfirmClearDialog(onDismiss: () -> Unit, onIconClear: () -> Unit) {
    AlertDialog(
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.outline,
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(R.string.confirmClear)) },
        text = {
            Text(stringResource(R.string.confirmClearText))
        },
        confirmButton = {
            IconButton(onClick = {
                onDismiss()
                onIconClear()
            }) {
                Icon(
                    imageVector = Icons.Filled.Done,
                    contentDescription = stringResource(R.string.confirm),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            IconButton(onClick = {
                onDismiss()
            }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.dismiss),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

@Composable
fun TabOptions(
    iconPacks: List<IconPack>,
    app: PackageInfoStruct,
    onChange: (options: IndividualOptions) -> Unit
) {
    var tabIndex by remember { mutableIntStateOf(0) }

    val tabs = listOf(
        stringResource(id = R.string.create),
        stringResource(id = R.string.upload),
        stringResource(id = R.string.editVector)
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = tabIndex) {
            tabs.forEachIndexed { index, title ->
                Tab(text = { Text(title) },
                    selected = tabIndex == index,
                    onClick = { tabIndex = index },
                    icon = {
                        when (index) {
                            0 -> Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                            1 -> Icon(imageVector = Icons.Filled.Face, contentDescription = null)
                            2 -> Icon(imageVector = Icons.Filled.Create, contentDescription = null)
                        }
                    }
                )
            }
        }
        onChange(EmptyOptions())
        when (tabIndex) {
            0 -> CreateColumn(iconPacks, onChange)
            1 -> UploadColumn(onChange)
            2 -> PrepareEditVector(app, onChange)
        }
    }
}

@Composable
fun CreateColumn(
    iconPacks: List<IconPack>,
    onChange: (options: IndividualOptions) -> Unit
) {
    var genType by rememberSaveable { mutableStateOf(GenerationType.PATH) }
    var useVector by rememberSaveable { mutableStateOf(false) }
    var useMonochrome by rememberSaveable { mutableStateOf(false) }
    var iconColor by rememberSaveable(saver = colorSaver()) { mutableStateOf(Color.White) }

    var iconPack by rememberSaveable { mutableStateOf("") }

    Column {
        TypeDropdown(genType) { genType = it }
        IconPackDropdown(iconPacks, iconPack) { iconPack = it.packageName }
        ColorButton(stringResource(R.string.iconColor), Color.White) { iconColor = it }

        if (genType == GenerationType.PATH) {
            VectorSwitch(useVector) { useVector = it }
            MonochromeSwitch(useMonochrome) { useMonochrome = it }
        }

        val generatingOptions = IconGenerator.GenerationOptions(android.graphics.Color.parseColor(iconColor.toHexString()), useMonochrome, useVector)
        onChange(CreatedOptions(generatingOptions, genType, iconPack))
    }
}

@Composable
fun UploadColumn(onChange: (options: IndividualOptions) -> Unit) {
    var imageUri by rememberSaveable { mutableStateOf(Uri.EMPTY) }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        UploadButton { imageUri = it }
        if (imageUri != Uri.EMPTY) {
            AsyncImage(imageUri, contentDescription = null)

            val contentResolver = getCurrentContext().contentResolver
            val uploadedImage = contentResolver.openInputStream(imageUri).use { BitmapFactory.decodeStream(it) }

            onChange(UploadedOptions(uploadedImage))
        }
    }
}

@Composable
fun UploadButton(onChange: (newValue: Uri) -> Unit) {
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

@Composable
fun PrepareEditVector(app: PackageInfoStruct, onChange: (options: IndividualOptions) -> Unit) {
    val editedVector = if (app.createdIcon is VectorIcon) {
        app.createdIcon.vector.toMutableImageVector().applyAndRemoveGroup()
    } else {
        createEmptyVector().toMutableImageVector()
    }

    EditVectorColumn(editedVector, onChange)
}

@Composable
fun EditVectorColumn(editedVector: MutableImageVector, onChange: (options: IndividualOptions) -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        var paths: List<MutableVectorPath> by rememberSaveable { mutableStateOf(listOf()) }
        var firstLoad by rememberSaveable { mutableStateOf(true) }

        if (firstLoad) {
            for (path in editedVector.root.children) {
                if (path is MutableVectorPath) {
                    val mutableList = paths.toMutableList()
                    mutableList.add(path)
                    paths = mutableList.toList()
                }
            }

            firstLoad = false
        }

        editedVector.root.children.clear()
        editedVector.root.children.addAll(paths)
        editedVector.center()

        val painter = rememberVectorPainter(editedVector.toImageVector())
        Image(painter, null, Modifier
            .padding(2.dp)
            .size(78.dp, 78.dp))

        LazyColumn {
            itemsIndexed(paths) { index, path ->
                VectorPathItem(editedVector.toImageVector(), path, index) {
                    val mutableList = paths.toMutableList()
                    mutableList.removeAt(index)
                    paths = mutableList.toList()
                }
            }
        }
        
        onChange(EditedVectorOptions(editedVector.toImageVector()))
    }
}

@Composable
fun VectorPathItem(vector: ImageVector, path: MutableVectorPath, index: Int, onDelete: () -> Unit) {
    val newVector = vector.toMutableImageVector()
    newVector.root.children.clear()
    newVector.root.children.add(path)

    Row(modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            onDelete()
        }) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Clear",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        val painter = rememberVectorPainter(newVector.toImageVector())
        Image(painter, null, Modifier
            .padding(2.dp)
            .size(78.dp, 78.dp))
        //Text(text = path.pathData.toStringPath())
    }
}

interface IndividualOptions

class EmptyOptions: IndividualOptions

data class CreatedOptions(
    val generatingOptions: IconGenerator.GenerationOptions,
    val generatingType: GenerationType,
    val iconPackageName: String
): IndividualOptions

data class UploadedOptions(
    val uploadedImage: Bitmap
): IndividualOptions

data class EditedVectorOptions(
    val editedVector: ImageVector
): IndividualOptions