@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color.Companion.Red
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.DialogShape
import java.io.File

internal data class GallerySelection(val paths: Set<String> = emptySet()) {
    val active: Boolean get() = paths.isNotEmpty()

    fun toggle(path: String) = GallerySelection(
        if (path in paths) paths - path else paths + path
    )

    fun toggleAll(availablePaths: Set<String>) = GallerySelection(
        if (paths == availablePaths) emptySet() else availablePaths
    )
}

@Composable
internal fun UploadGalleryContent(
    savedImages: List<File>,
    uploadedImage: Bitmap?,
    mask: Bitmap?,
    asAdaptiveIcon: Boolean,
    zoomLevel: Float,
    selectedImagePath: String?,
    selection: GallerySelection,
    onAdaptiveIconChange: (Boolean) -> Unit,
    onZoomChange: (Float) -> Unit,
    onSelectedImageChange: (String?) -> Unit,
    onSelectionChange: (GallerySelection) -> Unit,
    onChange: (IconPackDrawable?, String?) -> Unit
) {
    if (savedImages.isEmpty() && uploadedImage == null) {
        UploadGalleryEmptyState()
        return
    }

    val galleryGridState = rememberLazyGridState()
    LazyVerticalGrid(
        state = galleryGridState,
        columns = GridCells.Fixed(GALLERY_COLUMNS),
        modifier = Modifier
            .fillMaxSize()
            .drawVerticalScrollbar(galleryGridState, spanCount = GALLERY_COLUMNS),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (uploadedImage != null) {
            item(key = "editor", span = { GridItemSpan(maxLineSpan) }) {
                UploadedImageEditor(
                    image = uploadedImage,
                    mask = mask,
                    asAdaptiveIcon = asAdaptiveIcon,
                    zoomLevel = zoomLevel,
                    selectedImagePath = selectedImagePath,
                    onAdaptiveIconChange = onAdaptiveIconChange,
                    onZoomChange = onZoomChange,
                    onChange = onChange
                )
            }
        }

        item(key = "gallery_header", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = stringResource(R.string.yourImages),
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }

        items(savedImages, key = { it.absolutePath }) { file ->
            val path = file.absolutePath
            UploadedImageThumbnail(
                file = file,
                selected = !selection.active && path == selectedImagePath,
                marked = path in selection.paths,
                onClick = {
                    if (selection.active) {
                        onSelectionChange(selection.toggle(path))
                    } else {
                        onSelectedImageChange(path.takeUnless { it == selectedImagePath })
                    }
                },
                onLongClick = {
                    if (!selection.active) onSelectionChange(GallerySelection(setOf(path)))
                }
            )
        }
    }
}

@Composable
private fun UploadedImageEditor(
    image: Bitmap,
    mask: Bitmap?,
    asAdaptiveIcon: Boolean,
    zoomLevel: Float,
    selectedImagePath: String?,
    onAdaptiveIconChange: (Boolean) -> Unit,
    onZoomChange: (Float) -> Unit,
    onChange: (IconPackDrawable?, String?) -> Unit
) {
    val zoomedImage = remember(image, zoomLevel) { zoomBitmap(image, zoomLevel) }
    LaunchedEffect(zoomedImage, asAdaptiveIcon, selectedImagePath) {
        onChange(BitmapIconDrawable(zoomedImage, asAdaptiveIcon), selectedImagePath)
    }

    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Image(
                    painter = BitmapPainter(image.asImageBitmap()),
                    contentDescription = null,
                    modifier = Modifier.padding(2.dp).size(108.dp)
                )
                if (asAdaptiveIcon && mask != null) {
                    AdaptiveImagePreview(zoomedImage, mask)
                }
            }
            if (asAdaptiveIcon) Text(stringResource(R.string.deadZone), color = Red)
            AdaptiveIconSwitch(asAdaptiveIcon, onChange = onAdaptiveIconChange)
            if (asAdaptiveIcon) ZoomSlider(zoomLevel, onChange = onZoomChange)
        }
    }
}

@Composable
private fun AdaptiveImagePreview(image: Bitmap, mask: Bitmap) {
    Image(
        painter = BitmapPainter(image.asImageBitmap()),
        contentDescription = null,
        modifier = Modifier
            .padding(2.dp)
            .size(108.dp)
            .drawWithContent {
                drawContent()
                drawImage(
                    mask.asImageBitmap(),
                    srcSize = IntSize(mask.width, mask.height),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    blendMode = BlendMode.Overlay
                )
            }
    )
}

@Composable
private fun UploadGalleryEmptyState() {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
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
}

@Composable
internal fun UploadGalleryActions(
    savedImages: List<File>,
    selection: GallerySelection,
    onSelectionChange: (GallerySelection) -> Unit,
    onDelete: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!selection.active) {
        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.addImages)) },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = modifier.padding(16.dp)
        )
        return
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(16.dp),
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
                IconButton(onClick = { onSelectionChange(GallerySelection()) }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.dismiss))
                }
                Text(
                    text = "${selection.paths.size}",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                val availablePaths = savedImages.mapTo(mutableSetOf()) { it.absolutePath }
                val allSelected = selection.paths == availablePaths
                IconButton(onClick = {
                    onSelectionChange(selection.toggleAll(availablePaths))
                }) {
                    SelectAllIcon(allSelected)
                }
            }
        }
        FloatingActionButton(
            onClick = onDelete,
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ) {
            Icon(Icons.Filled.Delete, stringResource(R.string.deleteImage))
        }
    }
}

@Composable
private fun SelectAllIcon(allSelected: Boolean) {
    AnimatedContent(
        targetState = allSelected,
        transitionSpec = {
            (fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
                scaleIn(
                    initialScale = 0.6f,
                    animationSpec = spring(Spring.DampingRatioMediumBouncy)
                )) togetherWith
                (fadeOut(spring(stiffness = Spring.StiffnessHigh)) + scaleOut(targetScale = 0.6f))
        },
        label = "selectAllToggle"
    ) { deselect ->
        if (deselect) {
            Icon(Icons.Filled.Deselect, stringResource(R.string.deselectAll))
        } else {
            Icon(Icons.Filled.SelectAll, stringResource(R.string.selectAll))
        }
    }
}

@Composable
internal fun UploadLoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = DialogShape,
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

private const val GALLERY_COLUMNS = 4
