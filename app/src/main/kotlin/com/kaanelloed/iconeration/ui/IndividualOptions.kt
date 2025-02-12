package com.kaanelloed.iconeration.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Red
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.EmptyPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import com.kaanelloed.iconeration.R
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.data.IconPack
import com.kaanelloed.iconeration.data.ImageEdit
import com.kaanelloed.iconeration.data.Source
import com.kaanelloed.iconeration.data.TextType
import com.kaanelloed.iconeration.drawable.DrawableExtension.Companion.shrinkIfBiggerThan
import com.kaanelloed.iconeration.drawable.ResourceDrawable
import com.kaanelloed.iconeration.extension.toDrawable
import com.kaanelloed.iconeration.icon.BitmapIcon
import com.kaanelloed.iconeration.icon.EmptyIcon
import com.kaanelloed.iconeration.icon.ExportableIcon
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.icon.creator.GenerationOptions
import com.kaanelloed.iconeration.packages.ApplicationManager
import com.kaanelloed.iconeration.vector.ImageVectorExtension.Companion.createEmptyVector
import com.kaanelloed.iconeration.vector.ImageVectorExtension.Companion.getBuilder
import com.kaanelloed.iconeration.vector.MutableImageVector.Companion.toMutableImageVector
import com.kaanelloed.iconeration.vector.MutableVectorPath
import com.kaanelloed.iconeration.vector.PathExporter.Companion.toStringPath
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.applyAndRemoveGroup
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.center
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

const val MIME_TYPE_IMAGE = "image/*"

@Composable
fun OptionsDialog(
    iconPacks: List<IconPack>,
    app: PackageInfoStruct,
    onConfirm: (icon: ExportableIcon) -> Unit,
    onDismiss: () -> Unit,
    onIconClear: () -> Unit
) {
    var icon: ExportableIcon = EmptyIcon()

    AlertDialog(
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.outline,
        onDismissRequest = onDismiss,
        title = { DialogTitle(app = app, onIconClear) },
        text = {
            TabOptions(iconPacks, app) {
                icon = it
            }
        },
        confirmButton = {
            IconButton(onClick = {
                onConfirm(icon)
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
    onChange: (icon: ExportableIcon) -> Unit
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
        onChange(EmptyIcon())
        when (tabIndex) {
            0 -> CreateColumn(iconPacks, app, onChange)
            1 -> UploadColumn(app, onChange)
            2 -> PrepareEditVector(app, onChange)
        }
    }
}

@Composable
fun CreateColumn(
    iconPacks: List<IconPack>,
    app: PackageInfoStruct,
    onChange: (icon: ExportableIcon) -> Unit
) {
    var iconList: List<ExportableIcon> by rememberSaveable { mutableStateOf(listOf(EmptyIcon())) }
    var customIconList: List<ResourceDrawable> by rememberSaveable { mutableStateOf(listOf()) }

    var source by rememberSaveable { mutableStateOf(Source.NONE) }
    var imageEdit by rememberSaveable { mutableStateOf(ImageEdit.NONE) }
    var textType by rememberSaveable { mutableStateOf(TextType.FULL_NAME) }
    var useVector by rememberSaveable { mutableStateOf(false) }
    var useMonochrome by rememberSaveable { mutableStateOf(false) }

    var iconColor by rememberSaveable(saver = colorSaver()) { mutableStateOf(Color.White) }
    var iconPack by rememberSaveable { mutableStateOf("") }

    val generatingOptions = GenerationOptions(source, imageEdit, textType, iconPack, iconColor.toInt(), 0, useVector, useMonochrome, false, true)

    val activity = getCurrentMainActivity()

    LaunchedEffect(generatingOptions, customIconList) {
        val custom = if (customIconList.isNotEmpty()) customIconList[0] else null
        val newIcon = activity.appProvider.getIcon(app, generatingOptions, custom)
        iconList = iconList.toMutableList().also { it[0] = newIcon }
    }

    val icon = iconList[0]

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        if (icon !is EmptyIcon) {
            Row(Modifier.fillMaxWidth()
                , horizontalArrangement = Arrangement.Center) {
                Image(painter = icon.getPainter()
                    , contentDescription = null
                    , modifier = Modifier
                        .padding(2.dp)
                        .size(78.dp, 78.dp))
            }
        }

        SourceDropdown(R.string.source, source) { source = it }

        if (needIconPack(source)) {
            IconPackDropdown(R.string.iconPack, iconPacks, iconPack, app.toInstalledApplication()) { iconPack = it.packageName }
        }

        if (isIconPackSelected(source, iconPack)) {
            Row(modifier = Modifier.fillMaxWidth()
                , horizontalArrangement = Arrangement.Center) {
                SearchIconPackButton(iconPack, generatingOptions) { resource, _ ->
                    customIconList = customIconList.toMutableList().also {
                        if (it.isEmpty()) {
                            it.add(resource)
                        } else {
                            it[0] = resource
                        }
                    }
                }

                if (customIconList.isNotEmpty()) {
                    IconButton(onClick = {
                        customIconList = listOf()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (needImageEdit(source)) {
            ImageEditDropdown(R.string.imageEdit, imageEdit) { imageEdit = it }
        }

        if (needTextType(source)) {
            TextTypeDropdown(R.string.textType, textType) { textType = it }
        }

        ColorButton(stringResource(R.string.iconColor), Color.White) { iconColor = it }

        if (isPathTracingEnabled(source, imageEdit)) {
            VectorSwitch(useVector) { useVector = it }
            MonochromeSwitch(useMonochrome) { useMonochrome = it }
        }

        onChange(icon)
    }
}

@Composable
fun UploadColumn(app: PackageInfoStruct,
                 onChange: (icon: ExportableIcon) -> Unit) {
    var imageModifier by rememberSaveable { mutableStateOf(ImageEdit.NONE) }
    var imageUri by rememberSaveable { mutableStateOf(Uri.EMPTY) }
    var asAdaptiveIcon by rememberSaveable { mutableStateOf(false) }
    var zoomLevel by rememberSaveable { mutableFloatStateOf(1f) }
    var uploadedImage by remember { mutableStateOf(null as Bitmap?) }
    var modifiedImage by remember { mutableStateOf(null as Bitmap?) }
    var mask by remember { mutableStateOf(null as Bitmap?) }
    var iconColor by rememberSaveable(saver = colorSaver()) { mutableStateOf(Color.White) }
    var uploadError by remember { mutableStateOf(false) }
    val maxSize = 500

    val activity = getCurrentMainActivity()
    val context = getCurrentContext()
    val res = context.resources

    LaunchedEffect(imageUri) {
        if (imageUri != Uri.EMPTY) {
            uploadedImage = getBitmapFromURI(context, imageUri)?.toDrawable(res)?.shrinkIfBiggerThan(maxSize)

            if (uploadedImage != null) {
                uploadedImage = squareBitmap(uploadedImage!!)
                mask = createMask(uploadedImage!!)
            } else {
                uploadError = true
            }
        }
    }

    LaunchedEffect(imageUri, imageModifier, iconColor) {
        if (uploadedImage != null) {
            val generatingOptions = GenerationOptions(Source.ICON_PACK, imageModifier, TextType.FULL_NAME, "", iconColor.toInt(), 0, false, false, false, true)
            modifiedImage = activity.appProvider.getIcon(app, generatingOptions, ResourceDrawable(0, uploadedImage!!.toDrawable(res))).toBitmap()
        }
    }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        UploadButton { imageUri = it }
        if (imageUri != Uri.EMPTY) {
            if (uploadError) {
                ShowToast(stringResource(R.string.uploadImageError))
                uploadError = false
                return
            }

            if (modifiedImage == null) {
                return
            }

            val zoomedImage = zoomBitmap(modifiedImage!!, zoomLevel)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Image(
                    painter = BitmapPainter(modifiedImage!!.asImageBitmap()),
                    contentDescription = null,
                    //contentScale = ContentScale.Inside,
                    modifier = Modifier
                        .padding(2.dp)
                        .size(108.dp, 108.dp)
                )
                
                if (asAdaptiveIcon) {
                    Image(
                        painter = BitmapPainter(zoomedImage.asImageBitmap()),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(2.dp)
                            .size(108.dp, 108.dp)
                            .drawWithContent {
                                drawContent()
                                drawImage(
                                    mask!!.asImageBitmap(),
                                    srcSize = IntSize(mask!!.width, mask!!.height),
                                    dstSize = IntSize(
                                        this.size.width.toInt(),
                                        this.size.height.toInt()
                                    ),
                                    blendMode = BlendMode.Overlay
                                )
                            }
                    )
                }
            }

            if (asAdaptiveIcon) {
                Text(stringResource(R.string.deadZone), color = Red)
            }

            AdaptiveIconSwitch(asAdaptiveIcon, onChange = { asAdaptiveIcon = it; zoomLevel = 1f })
            
            if (asAdaptiveIcon) {
                ZoomSlider(zoomLevel, onChange = { zoomLevel = it })
            }

            ImageEditDropdown(R.string.imageEdit, imageModifier) { imageModifier = it }

            if (imageModifier != ImageEdit.NONE) {
                ColorButton(stringResource(R.string.iconColor), Color.White) { iconColor = it }
            }

            onChange(BitmapIcon(zoomedImage, asAdaptiveIcon))
        }
    }
}

private fun getBitmapFromURI(context: Context, uri: Uri): Bitmap? {
    val contentResolver = context.contentResolver

    var bitmap = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }

    if (bitmap == null) {
        val svg = contentResolver.openInputStream(uri).use { decodeSVGSteam(it) }

        if (svg != null) {
            if (svg.documentWidth > 0 && svg.documentHeight > 0) {
                bitmap = Bitmap.createBitmap(svg.documentWidth.toInt(), svg.documentHeight.toInt(), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                svg.renderToCanvas(canvas)
            }
        }
    }

    return bitmap ?: null
}

private fun decodeSVGSteam(stream: InputStream?): SVG? {
    if (stream == null)
        return null

    return try {
        SVG.getFromInputStream(stream)
    } catch (_: SVGParseException) {
        null
    }
}

@Composable
private fun zoomBitmap(image: Bitmap, zoomLevel: Float): Bitmap {
    if (zoomLevel == 1f) {
        return image
    }

    val x = (image.width - (image.width * zoomLevel)) / 2
    val y = (image.height - (image.height * zoomLevel)) / 2

    val zoomedImage = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
    val mtx = Matrix()
    mtx.postScale(zoomLevel, zoomLevel)
    mtx.postTranslate(x, y)

    val canvas = Canvas(zoomedImage)
    canvas.drawBitmap(image, mtx, Paint())

    return zoomedImage
}

private fun squareBitmap(image: Bitmap): Bitmap {
    if (image.width == image.height) {
        return image
    }

    val size = max(image.width, image.height)
    val squaredImage = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    val x = (size - image.width) / 2f
    val y = (size - image.height) / 2f

    val mtx = Matrix()
    mtx.postTranslate(x, y)

    val canvas = Canvas(squaredImage)
    canvas.drawBitmap(image, mtx, Paint())

    return squaredImage
}

private fun createMask(image: Bitmap): Bitmap {
    val startActiveZone = image.width / 6f
    val topActiveZone = image.height / 6f
    val endActiveZone = image.width - startActiveZone
    val bottomActiveZone = image.height - topActiveZone

    val path = Path()
    path.moveTo(0f, 0f)
    path.lineTo(image.width.toFloat(), 0f)
    path.lineTo(image.width.toFloat(), image.height.toFloat())
    path.lineTo(0f, image.height.toFloat())
    path.close()

    path.moveTo(startActiveZone, topActiveZone)
    path.lineTo(startActiveZone, bottomActiveZone)
    path.lineTo(endActiveZone, bottomActiveZone)
    path.lineTo(endActiveZone, topActiveZone)
    path.close()

    val paint = Paint()
    paint.color = Red.toArgb()
    paint.style = Paint.Style.FILL

    val mask = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
    val maskCanvas = Canvas(mask)
    maskCanvas.drawPath(path.asAndroidPath(), paint)

    return mask
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
        launcher.launch(MIME_TYPE_IMAGE)
    }) {
        Text(stringResource(R.string.uploadImage))
    }
}

@Composable
fun ZoomSlider(value: Float, onChange: (newValue: Float) -> Unit) {
    var sliderPosition by remember { mutableFloatStateOf(value) }

    Slider(
        value = sliderPosition,
        onValueChange = {
            sliderPosition = it
            onChange(it)},
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.secondary,
            activeTrackColor = MaterialTheme.colorScheme.secondary,
            inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        steps = 0,
        valueRange = 0f..2f
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = (sliderPosition * 100).toInt().toString() + "%")
        IconButton(onClick = {
            sliderPosition = 1f
            onChange(sliderPosition)
        }) {
            Icon(
                imageVector = Icons.Filled.Clear,
                contentDescription = "Clear",
                tint = MaterialTheme.colorScheme.primary
            )
        }
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
fun PrepareEditVector(app: PackageInfoStruct, onChange: (icon: ExportableIcon) -> Unit) {
    val editedVector = if (app.createdIcon is VectorIcon) {
        app.createdIcon.vector.toMutableImageVector().applyAndRemoveGroup().toImageVector()
    } else {
        createEmptyVector()
    }

    EditVectorColumn(editedVector, onChange)
}

@Composable
fun EditVectorColumn(vector: ImageVector, onChange: (icon: ExportableIcon) -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        var paths: List<VectorPath> by remember { mutableStateOf(listOf()) }
        var firstLoad by remember { mutableStateOf(true) }
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

        onChange(VectorIcon(editedVector.toImageVector()))
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

@Composable
fun SearchIconPackButton(iconPackageName: String, options: GenerationOptions, onSelect: (ResourceDrawable, ExportableIcon) -> Unit) {
    var showPackDrawables by rememberSaveable { mutableStateOf(false) }

    val context = getCurrentContext()
    val activity = getCurrentMainActivity()

    IconButton(onClick = {
        showPackDrawables = true
    }) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = "Search",
            tint = MaterialTheme.colorScheme.primary
        )
    }

    if (showPackDrawables) {
        val iconPack = activity.appProvider.iconPacks.find { it.packageName == iconPackageName }!!
        val drawNames = ApplicationManager(context).getIconPackDrawableNames(iconPackageName)

        GridImageList(iconPack, drawNames, options, onDismiss = {
            showPackDrawables = false
        }) { resource, icon ->
            showPackDrawables = false
            onSelect(resource, icon)
        }
    }
}

@Composable
fun GridImageList(iconPack: IconPack
                  , drawableNames: List<String>
                  , options: GenerationOptions
                  , onDismiss: () -> Unit
                  , onSelect: (ResourceDrawable, ExportableIcon) -> Unit) {
    val context = getCurrentContext()
    val activity = getCurrentMainActivity()
    val appMan = ApplicationManager(context)
    val itemsPerPage = 9

    var nameFilter by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(1) }

    val formattedNameFilter = nameFilter.lowercase().trim().replace(' ', '_')
    val filteredDrawableNames = drawableNames.filter { it.contains(formattedNameFilter) }

    val startIndex = (page - 1) * itemsPerPage
    val endIndex = min(startIndex + itemsPerPage, filteredDrawableNames.lastIndex)
    val names = filteredDrawableNames.subList(startIndex, endIndex + 1)

    val ids = appMan.getIconPackDrawableIds(iconPack.packageName, names)
    val drawables = appMan.getIconPackDrawables(iconPack.packageName, ids.subList(0, min(itemsPerPage, ids.size)))
    val exportDrawables = activity.appProvider.getIconPackIcons(iconPack.packageName, options, drawables)

    val havePreviousPage = page > 1
    val haveNextPage = ids.count() > itemsPerPage

    AlertDialog(
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.outline,
        onDismissRequest = { onDismiss() },
        title = { Text(iconPack.applicationName) },
        text = {
            Column {
                SearchBar {
                    nameFilter = it
                    page = 1
                }
                GridImageList(exportDrawables) { resource, icon ->
                    onSelect(resource, icon)
                }
                PageChanger(page
                    , havePreviousPage
                    , haveNextPage
                    , goToPrevious = {
                        page--
                    }) {
                    page++
                }
            }
        },
        confirmButton = { },
        dismissButton = { }
    )
}

@Composable
fun GridImageList(drawables: Map<ResourceDrawable, ExportableIcon>, onSelect: (ResourceDrawable, ExportableIcon) -> Unit) {
    val list = drawables.map { Pair(it.key, it.value) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3)
    ) {
        items(list) { image ->
            Image(painter = image.second.getPainter()
                , ""
                , Modifier.clickable { onSelect(image.first, image.second) })
        }
    }
}

@Composable
fun PageChanger(page: Int
                , havePreviousPage: Boolean
                , haveNextPage: Boolean
                , goToPrevious: () -> Unit
                , goToNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        IconButton(onClick = {
            goToPrevious()
        }, enabled = havePreviousPage) {
            if (havePreviousPage) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Previous",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        IconButton(onClick = { }, enabled = false) {
            Text(page.toString(), Modifier.padding(8.dp), MaterialTheme.colorScheme.primary)
        }

        IconButton(onClick = {
            goToNext()
        }, enabled = haveNextPage) {
            if (haveNextPage) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Next",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}