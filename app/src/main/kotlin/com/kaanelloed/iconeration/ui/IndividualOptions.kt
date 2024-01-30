package com.kaanelloed.iconeration.ui

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.EmptyPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kaanelloed.iconeration.R
import com.kaanelloed.iconeration.icon.creator.IconGenerator
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.data.GenerationType
import com.kaanelloed.iconeration.data.IconPack
import com.kaanelloed.iconeration.drawable.DrawableExtension.Companion.shrinkIfBiggerThan
import com.kaanelloed.iconeration.drawable.DrawableExtension.Companion.toDrawable
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.vector.ImageVectorExtension.Companion.createEmptyVector
import com.kaanelloed.iconeration.vector.ImageVectorExtension.Companion.getBuilder
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
    var asAdaptiveIcon by rememberSaveable { mutableStateOf(false) }
    val maxSize = 500

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        UploadButton { imageUri = it }
        if (imageUri != Uri.EMPTY) {
            AsyncImage(imageUri, contentDescription = null)
            AdaptiveIconSwitch(asAdaptiveIcon, onChange = { asAdaptiveIcon = it })

            val contentResolver = getCurrentContext().contentResolver
            val uploadedImage = contentResolver.openInputStream(imageUri).use { BitmapFactory.decodeStream(it) }

            onChange(UploadedOptions(uploadedImage.toDrawable().shrinkIfBiggerThan(maxSize), asAdaptiveIcon))
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
fun AdaptiveIconSwitch(asAdaptiveIcon: Boolean, onChange: (newValue: Boolean) -> Unit) {
    var checked by rememberSaveable { mutableStateOf(false) }

    checked = asAdaptiveIcon

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.asAdaptiveIcon))
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
fun PrepareEditVector(app: PackageInfoStruct, onChange: (options: IndividualOptions) -> Unit) {
    val editedVector = if (app.createdIcon is VectorIcon) {
        app.createdIcon.vector.toMutableImageVector().applyAndRemoveGroup().toImageVector()
    } else {
        createEmptyVector()
    }

    EditVectorColumn(editedVector, onChange)
}

@Composable
fun EditVectorColumn(vector: ImageVector, onChange: (options: IndividualOptions) -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        var paths: List<VectorPath> by rememberSaveable { mutableStateOf(listOf()) }
        var firstLoad by rememberSaveable { mutableStateOf(true) }
        var automaticallyCenter by rememberSaveable { mutableStateOf(true) }

        if (firstLoad) {
            for (path in vector.root) {
                if (path is VectorPath && path.pathData != EmptyPath) {
                    val mutableList = paths.toMutableList()
                    mutableList.add(path)
                    paths = mutableList.toList()
                }
            }

            firstLoad = false
        }

        val editedVector = vector.toMutableImageVector()

        editedVector.root.children.clear()
        for (path in paths) {
            editedVector.root.children.add(MutableVectorPath(path))
        }
        if (automaticallyCenter)
            editedVector.center()

        val painter = rememberVectorPainter(editedVector.toImageVector())
        Row {
            Image(painter, null, Modifier
                .padding(2.dp)
                .size(78.dp, 78.dp))
        }

        CenterSwitch {
            automaticallyCenter = it
        }

        NewPath {
            if (it.trim() == "") {
                return@NewPath
            }

            var stroke = SolidColor(Color.White) as Brush?
            var strokeWidth = 1F

            val lastPath = editedVector.root.children.lastOrNull() as MutableVectorPath?
            if (lastPath != null) {
                stroke = lastPath.stroke
                strokeWidth = lastPath.strokeLineWidth
            }

            val parser = PathParser().parsePathString(it)

            val builder = editedVector.toImageVector().getBuilder()
            builder.addPath(parser.toNodes(), stroke = stroke, strokeLineWidth = strokeWidth)
            val newVector = builder.build()

            val newPath = newVector.root.last() as VectorPath

            val mutableList = paths.toMutableList()
            mutableList.add(newPath)
            paths = mutableList.toList()
        }

        LazyColumn {
            itemsIndexed(paths) { index, path ->
                VectorPathItem(editedVector.toImageVector(), path, {
                    val mutableList = paths.toMutableList()
                    mutableList.removeAt(index)
                    paths = mutableList.toList()
                }) {
                    val parser = PathParser().parsePathString(it)

                    val mutablePath = editedVector.root.children[index] as MutableVectorPath
                    mutablePath.pathData.clear()
                    mutablePath.pathData.addAll(parser.toNodes())

                    val newPath = editedVector.toImageVector().root[index] as VectorPath

                    val mutableList = paths.toMutableList()
                    mutableList[index] = newPath
                    paths = mutableList.toList()
                }
            }
        }

        onChange(EditedVectorOptions(editedVector.toImageVector()))
    }
}

@Composable
fun VectorPathItem(
    vector: ImageVector,
    path: VectorPath,
    onDelete: () -> Unit,
    onChange: (newPath: String) -> Unit
) {
    var showPathEditor by remember { mutableStateOf(false) }

    val newVector = vector.toMutableImageVector()
    newVector.root.children.clear()
    newVector.root.children.add(MutableVectorPath(path))

    Row(modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            onDelete()
        }) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Clear",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        IconButton(onClick = {
            showPathEditor = true
        }) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Edit",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        val painter = rememberVectorPainter(newVector.toImageVector())
        Image(painter, null, Modifier
            .padding(2.dp)
            .size(78.dp, 78.dp))
    }

    if (showPathEditor) {
        EditPathDialog(path.pathData.toStringPath(), { showPathEditor = false }) {
            onChange(it)
            showPathEditor = false
        }
    }
}

@Composable
fun NewPath(onChange: (newPath: String) -> Unit) {
    var showPathEditor by remember { mutableStateOf(false) }

    Row {
        IconButton(onClick = {
            showPathEditor = true
        }) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Add",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (showPathEditor) {
        EditPathDialog("", { showPathEditor = false }) {
            onChange(it)
            showPathEditor = false
        }
    }
}

@Composable
fun EditPathDialog(path: String, onDismiss: () -> Unit, onChange: (newPath: String) -> Unit) {
    var newPath by rememberSaveable { mutableStateOf(path) }
    var badFormatting by rememberSaveable { mutableStateOf(false) }
    var formatError by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.outline,
        onDismissRequest = { onDismiss() },
        title = { },
        text = {
            Column {
                TextField(newPath, onValueChange = {
                    newPath = it
                    badFormatting = false
                })

                if (badFormatting) {
                    Text(stringResource(id = R.string.badPathFormat),
                        color = MaterialTheme.colorScheme.error)
                    Text(formatError,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            val emptyPathText = stringResource(id = R.string.emptyPath)
            IconButton(onClick = {
                try {
                    val nodes = PathParser().parsePathString(newPath).toNodes()

                    if (nodes == EmptyPath) {
                        badFormatting = true
                        formatError = emptyPathText
                    }
                } catch (e: IllegalArgumentException) {
                    badFormatting = true
                    formatError = e.localizedMessage!!
                }

                if (!badFormatting) {
                    onChange(newPath)
                }
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
fun CenterSwitch(onChange: (newValue: Boolean) -> Unit) {
    var checked by rememberSaveable { mutableStateOf(true) }

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.automaticallyCenter))
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