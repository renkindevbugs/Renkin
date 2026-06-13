package dev.alembiconsProject.alembicons.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.EmptyPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.drawable.ImageVectorDrawable
import dev.alembiconsProject.alembicons.drawable.InsetIconDrawable
import dev.alembiconsProject.alembicons.drawable.MutableVectorPath
import dev.alembiconsProject.alembicons.drawable.toImageVectorDrawable
import dev.alembiconsProject.alembicons.extension.createEmptyVector
import dev.alembiconsProject.alembicons.extension.getBuilder
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import dev.alembiconsProject.alembicons.vector.PathExporter.Companion.toStringPath
import dev.alembiconsProject.alembicons.vector.VectorEditor.Companion.applyAndRemoveGroup
import dev.alembiconsProject.alembicons.vector.VectorEditor.Companion.center

@Composable
fun PrepareEditVector(app: PackageInfoStruct, onChange: (icon: IconPackDrawable?) -> Unit) {
    val editedVector = when (app.createdIcon) {
        is ImageVectorDrawable -> app.createdIcon.applyAndRemoveGroup().toImageVector()
        is InsetIconDrawable -> {
            if (app.createdIcon.drawable is ImageVectorDrawable)
                app.createdIcon.drawable.applyAndRemoveGroup().toImageVector()
            else
                ImageVector.createEmptyVector()
        }
        else -> ImageVector.createEmptyVector()
    }

    EditVectorColumn(editedVector) {
        if (app.createdIcon is InsetIconDrawable && it != null) {
            onChange(InsetIconDrawable(it, app.createdIcon.dimensions, app.createdIcon.fractions))
        } else {
            onChange(it)
        }
    }
}

@Composable
fun EditVectorColumn(vector: ImageVector, onChange: (icon: IconPackDrawable?) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        var paths: List<VectorPath> by remember { mutableStateOf(listOf()) }
        var automaticallyCenter by rememberSaveable { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            for (path in vector.root) {
                if (path is VectorPath && path.pathData != EmptyPath) {
                    val mutableList = paths.toMutableList()
                    mutableList.add(path)
                    paths = mutableList.toList()
                }
            }
        }

        val editedVector = vector.toImageVectorDrawable()

        editedVector.root.children.clear()
        for (path in paths) {
            editedVector.root.children.add(MutableVectorPath(path))
        }
        if (automaticallyCenter)
            editedVector.center()

        val painter = rememberVectorPainter(editedVector.toImageVector())
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Image(painter, null, Modifier
                .padding(16.dp)
                .size(96.dp, 96.dp))
        }

        CenterSwitch {
            automaticallyCenter = it
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.paths),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
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
        }

        if (paths.isEmpty()) {
            Text(
                text = stringResource(R.string.noPathsYet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
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

        // An empty vector is not a real icon — don't expose it (keeps Modifier greyed)
        onChange(if (paths.isEmpty()) null else editedVector)
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

    val newVector = vector.toImageVectorDrawable()
    newVector.root.children.clear()
    newVector.root.children.add(MutableVectorPath(path))

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                val painter = rememberVectorPainter(newVector.toImageVector())
                Image(painter, null, Modifier
                    .padding(6.dp)
                    .size(48.dp, 48.dp))
            }

            Text(
                text = path.pathData.toStringPath(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = { showPathEditor = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.editPath),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = { onDelete() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.clearIcons),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
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

    FilledTonalButton(onClick = { showPathEditor = true }) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = stringResource(R.string.addPath),
            modifier = Modifier.padding(start = 6.dp)
        )
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
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(R.string.editPath)) },
        text = {
            Column {
                OutlinedTextField(
                    value = newPath,
                    onValueChange = {
                        newPath = it
                        badFormatting = false
                    },
                    label = { Text(stringResource(R.string.pathData)) },
                    placeholder = { Text("M 12 2 L 22 22 H 2 Z") },
                    minLines = 5,
                    maxLines = 10,
                    isError = badFormatting,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )

                if (badFormatting) {
                    Text(
                        text = stringResource(id = R.string.badPathFormat),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = formatError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
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
