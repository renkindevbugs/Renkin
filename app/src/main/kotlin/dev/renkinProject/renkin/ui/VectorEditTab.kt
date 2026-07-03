package dev.renkinProject.renkin.ui

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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ImageVectorDrawable
import dev.renkinProject.renkin.drawable.InsetIconDrawable
import dev.renkinProject.renkin.drawable.MutableVectorPath
import dev.renkinProject.renkin.drawable.toImageVectorDrawable
import dev.renkinProject.renkin.extension.createEmptyVector
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.vector.PathExporter.Companion.toStringPath
import dev.renkinProject.renkin.vector.VectorEditor.Companion.applyAndRemoveGroup
import dev.renkinProject.renkin.vector.VectorEditor.Companion.center

@Composable
internal fun PrepareEditVector(app: PackageInfoStruct, state: VectorEditState, onChange: (icon: IconPackDrawable?) -> Unit) {
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

    EditVectorColumn(editedVector, state) {
        if (app.createdIcon is InsetIconDrawable && it != null) {
            onChange(InsetIconDrawable(it, app.createdIcon.dimensions, app.createdIcon.fractions))
        } else {
            onChange(it)
        }
    }
}

/**
 * One drawable path being edited. [path] carries the geometry and the (preview)
 * colour; [filled] selects solid-fill vs stroke rendering (#117) and [baseStroke]
 * is the unscaled stroke width the thickness slider (#115) multiplies.
 */
internal data class PathEntry(val path: VectorPath, val filled: Boolean, val baseStroke: Float)

/**
 * Vector-editor working state, hoisted above the tab's [androidx.compose.animation.AnimatedContent]
 * (in OptionsDialog) so switching to another tab and back doesn't dispose it and wipe the
 * user's paths. Seeded once from the source vector via [initialized].
 */
internal class VectorEditState {
    var entries: List<PathEntry> by mutableStateOf(listOf())
    var thickness: Float by mutableStateOf(1f)
    var automaticallyCenter: Boolean by mutableStateOf(true)
    var initialized: Boolean by mutableStateOf(false)
}

/**
 * Builds the live drawable path. A filled path drops the stroke and is exported
 * solid — [setReferenceColorPaths] keys the fill/stroke choice off a zero stroke
 * width, so [filled] maps straight onto `strokeLineWidth == 0`.
 */
private fun PathEntry.toMutablePath(thickness: Float): MutableVectorPath {
    val color = path.stroke ?: path.fill ?: SolidColor(Color.White)
    return MutableVectorPath(path).also { mp ->
        if (filled) {
            mp.fill = color
            mp.fillAlpha = 1f
            mp.stroke = null
            mp.strokeLineWidth = 0f
        } else {
            mp.stroke = color
            mp.strokeAlpha = 1f
            mp.fill = null
            mp.strokeLineWidth = baseStroke * thickness
        }
    }
}

/** A single-path vector for the per-path preview thumbnail. */
private fun PathEntry.toPreviewVector(template: ImageVector, thickness: Float): ImageVector {
    val preview = template.toImageVectorDrawable()
    preview.root.children.clear()
    preview.root.children.add(toMutablePath(thickness))
    return preview.toImageVector()
}

@Composable
internal fun EditVectorColumn(vector: ImageVector, state: VectorEditState, onChange: (icon: IconPackDrawable?) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The pack's icons are authored at 1F stroke on a 48 viewport
        val defaultStroke = remember(vector) {
            (vector.viewportHeight / 48f).takeIf { it > 0f } ?: 1f
        }

        LaunchedEffect(Unit) {
            // Seed from the source vector only once — re-entering the tab keeps edits.
            if (state.initialized) return@LaunchedEffect
            val initial = mutableListOf<PathEntry>()
            for (path in vector.root) {
                if (path is VectorPath && path.pathData != EmptyPath) {
                    val filled = path.strokeLineWidth == 0f && path.fill != null
                    val base = if (path.strokeLineWidth > 0f) path.strokeLineWidth else defaultStroke
                    initial.add(PathEntry(path, filled, base))
                }
            }
            state.entries = initial.toList()
            state.initialized = true
        }

        val editedVector = vector.toImageVectorDrawable()
        editedVector.root.children.clear()
        for (entry in state.entries) {
            editedVector.root.children.add(entry.toMutablePath(state.thickness))
        }
        if (state.automaticallyCenter)
            editedVector.center()

        val painter = rememberVectorPainter(editedVector.toImageVector())
        Surface(
            shape = CardShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Image(painter, null, Modifier
                .padding(16.dp)
                .size(96.dp, 96.dp))
        }

        CenterSwitch(state.automaticallyCenter) {
            state.automaticallyCenter = it
        }

        ThicknessSlider(state.thickness) { state.thickness = it }

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

                val parser = PathParser().parsePathString(it)

                val builder = ImageVector.Builder(
                    defaultWidth = vector.defaultWidth,
                    defaultHeight = vector.defaultHeight,
                    viewportWidth = vector.viewportWidth,
                    viewportHeight = vector.viewportHeight
                )
                builder.addPath(parser.toNodes(), stroke = SolidColor(Color.White), strokeLineWidth = defaultStroke)
                val newPath = builder.build().root.first() as VectorPath

                state.entries = state.entries + PathEntry(newPath, filled = false, baseStroke = defaultStroke)
            }
        }

        if (state.entries.isEmpty()) {
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
            itemsIndexed(state.entries) { index, entry ->
                VectorPathItem(
                    previewVector = entry.toPreviewVector(vector, state.thickness),
                    pathString = entry.path.pathData.toStringPath(),
                    filled = entry.filled,
                    onToggleFill = {
                        state.entries = state.entries.toMutableList().also {
                            it[index] = entry.copy(filled = !entry.filled)
                        }
                    },
                    onDelete = {
                        state.entries = state.entries.toMutableList().also { it.removeAt(index) }
                    },
                    onChange = { newPathString ->
                        val nodes = PathParser().parsePathString(newPathString).toNodes()

                        val builder = ImageVector.Builder(
                            defaultWidth = vector.defaultWidth,
                            defaultHeight = vector.defaultHeight,
                            viewportWidth = vector.viewportWidth,
                            viewportHeight = vector.viewportHeight
                        )
                        builder.addPath(nodes, stroke = entry.path.stroke, fill = entry.path.fill, strokeLineWidth = entry.baseStroke)
                        val rebuilt = builder.build().root.first() as VectorPath

                        state.entries = state.entries.toMutableList().also { it[index] = entry.copy(path = rebuilt) }
                    }
                )
            }
        }

        // An empty vector is not a real icon — don't expose it (keeps Modifier greyed)
        onChange(if (state.entries.isEmpty()) null else editedVector)
    }
}

@Composable
fun ThicknessSlider(thickness: Float, onChange: (newValue: Float) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.lineThickness),
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = thickness,
            onValueChange = onChange,
            valueRange = 0.25f..4f,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        )
    }
}

@Composable
fun VectorPathItem(
    previewVector: ImageVector,
    pathString: String,
    filled: Boolean,
    onToggleFill: () -> Unit,
    onDelete: () -> Unit,
    onChange: (newPath: String) -> Unit
) {
    var showPathEditor by remember { mutableStateOf(false) }

    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                val painter = rememberVectorPainter(previewVector)
                Image(painter, null, Modifier
                    .padding(6.dp)
                    .size(48.dp, 48.dp))
            }

            Text(
                text = pathString,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Solid-fill vs stroke for this path (#117)
            FilterChip(
                selected = filled,
                onClick = onToggleFill,
                label = {
                    Text(stringResource(if (filled) R.string.fillSolid else R.string.strokeLine))
                }
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
        EditPathDialog(pathString, { showPathEditor = false }) {
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

    RenkinAlertDialog(
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
fun CenterSwitch(checked: Boolean, onChange: (newValue: Boolean) -> Unit) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.automaticallyCenter))
        Switch(
            checked = checked,
            onCheckedChange = { onChange(it) },
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
