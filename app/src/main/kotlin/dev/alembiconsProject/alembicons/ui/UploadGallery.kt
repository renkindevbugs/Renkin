package dev.alembiconsProject.alembicons.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Red
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.data.ImageEdit
import dev.alembiconsProject.alembicons.data.UploadedImageStore
import dev.alembiconsProject.alembicons.drawable.BitmapIconDrawable
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.drawable.shrinkIfBiggerThan
import dev.alembiconsProject.alembicons.extension.toDrawable
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MIME_TYPE_IMAGE = "image/*"

@Composable
fun UploadColumn(app: PackageInfoStruct,
                 onChange: (icon: IconPackDrawable?) -> Unit) {
    var asAdaptiveIcon by rememberSaveable { mutableStateOf(false) }
    var zoomLevel by rememberSaveable { mutableFloatStateOf(1f) }
    var selectedImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    var savedImages by remember { mutableStateOf<List<File>>(emptyList()) }
    var uploadedImage by remember { mutableStateOf(null as Bitmap?) }
    var mask by remember { mutableStateOf(null as Bitmap?) }
    var uploadError by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var markedForDelete by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    val maxSize = 500

    val context = getCurrentContext()
    val res = context.resources
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        savedImages = withContext(Dispatchers.IO) { UploadedImageStore.list(context) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            isUploading = true
            try {
                var failed = false
                val added = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        val bitmap = getBitmapFromURI(context, uri)?.toDrawable(res)?.shrinkIfBiggerThan(maxSize)
                        if (bitmap == null) {
                            failed = true
                            null
                        } else {
                            UploadedImageStore.save(context, bitmap)
                        }
                    }
                }
                savedImages = withContext(Dispatchers.IO) { UploadedImageStore.list(context) }
                if (added.isNotEmpty()) selectedImagePath = added.first().absolutePath
                if (failed) uploadError = true
            } finally {
                isUploading = false
            }
        }
    }

    LaunchedEffect(selectedImagePath) {
        val path = selectedImagePath
        if (path == null) {
            uploadedImage = null
            onChange(null)
            return@LaunchedEffect
        }
        val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
        if (bitmap != null) {
            val squared = squareBitmap(bitmap)
            uploadedImage = squared
            mask = createMask(squared)
        } else {
            uploadError = true
        }
    }

    if (uploadError) {
        ShowToast(stringResource(R.string.uploadImageError))
        uploadError = false
    }

    BackHandler(enabled = selectionMode) {
        selectionMode = false
        markedForDelete = emptySet()
    }

    // Leave selection mode once nothing is selected (unselect-all or the last manual
    // deselect), so the contextual bar doesn't linger empty
    LaunchedEffect(selectionMode, markedForDelete) {
        if (selectionMode && markedForDelete.isEmpty()) {
            selectionMode = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (savedImages.isEmpty()) {
            // Big, centred empty state instead of a small line of text
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.AddPhotoAlternate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.noImagesYet),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.galleryEmptyHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val editedImage = uploadedImage
                if (editedImage != null) {
                    item(key = "editor", span = { GridItemSpan(maxLineSpan) }) {
                        val zoomedImage = zoomBitmap(editedImage, zoomLevel)

                        // Editor lives in a rounded card for a cleaner, modern look
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                    Image(
                                        painter = BitmapPainter(editedImage.asImageBitmap()),
                                        contentDescription = null,
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

                                onChange(BitmapIconDrawable(zoomedImage, asAdaptiveIcon))
                            }
                        }
                    }
                }

                item(key = "gallery_header", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.yourImages),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }

                items(savedImages, key = { it.absolutePath }) { file ->
                    val path = file.absolutePath
                    UploadedImageThumbnail(
                        file = file,
                        selected = !selectionMode && path == selectedImagePath,
                        marked = selectionMode && path in markedForDelete,
                        onClick = {
                            if (selectionMode) {
                                markedForDelete = if (path in markedForDelete) {
                                    markedForDelete - path
                                } else {
                                    markedForDelete + path
                                }
                            } else {
                                // Tapping the selected image again deselects it and the header
                                // falls back to the previously chosen icon
                                selectedImagePath = if (path == selectedImagePath) null else path
                            }
                        },
                        onLongClick = {
                            if (!selectionMode) {
                                selectionMode = true
                                markedForDelete = setOf(path)
                            }
                        }
                    )
                }
            }
        }

        // Selection mode swaps the add FAB for a contextual bar (cancel · count ·
        // select-all) next to a delete button
        if (selectionMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            selectionMode = false
                            markedForDelete = emptySet()
                        }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.dismiss))
                        }
                        Text(
                            text = "${markedForDelete.size}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            val all = savedImages.map { it.absolutePath }.toSet()
                            markedForDelete = if (markedForDelete.size == all.size) emptySet() else all
                        }) {
                            Icon(Icons.Filled.SelectAll, stringResource(R.string.selectAll))
                        }
                    }
                }
                FloatingActionButton(
                    onClick = { if (markedForDelete.isNotEmpty()) showDeleteConfirm = true },
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.deleteImage))
                }
            }
        } else {
            ExtendedFloatingActionButton(
                onClick = { launcher.launch(MIME_TYPE_IMAGE) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.addImages)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            )
        }

        // Loading overlay while the picked images are decoded and saved — a dimmed
        // backdrop with a card (spinner + label) so it's clear what's happening
        if (isUploading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.addingImages),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.outline,
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.deleteImage)) },
            text = { Text(stringResource(R.string.deleteImageText)) },
            confirmButton = {
                IconButton(onClick = {
                    showDeleteConfirm = false
                    val toDelete = savedImages.filter { it.absolutePath in markedForDelete }
                    selectionMode = false
                    markedForDelete = emptySet()
                    scope.launch {
                        withContext(Dispatchers.IO) { toDelete.forEach { UploadedImageStore.delete(it) } }
                        savedImages = withContext(Dispatchers.IO) { UploadedImageStore.list(context) }
                        if (toDelete.any { it.absolutePath == selectedImagePath }) selectedImagePath = null
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
                IconButton(onClick = { showDeleteConfirm = false }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.dismiss),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        )
    }
}

@Composable
private fun UploadedImageThumbnail(
    file: File,
    selected: Boolean,
    marked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var thumbnail by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(file.absolutePath) {
        // Grid cells are small, so decode a downsampled bitmap — decoding the full
        // ~500px image for every thumbnail is what made bulk uploads stutter
        thumbnail = withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }
    }

    val borderColor = when {
        marked -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(if (marked || selected) 2.dp else 1.dp, borderColor),
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(Modifier.fillMaxSize()) {
            val bmp = thumbnail
            if (bmp != null) {
                Image(
                    painter = BitmapPainter(bmp.asImageBitmap()),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                )
            }
            if (marked) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Done,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
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
