@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.UploadedImageLibrary
import dev.renkinProject.renkin.drawable.IconPackDrawable
import java.io.File
import kotlinx.coroutines.launch

const val MIME_TYPE_IMAGE = "image/*"

@Composable
internal fun UploadColumn(
    snackbarHostState: SnackbarHostState,
    state: UploadEditorState,
    library: UploadedImageLibrary,
    onChange: (icon: IconPackDrawable?, path: String?) -> Unit
) {
    var asAdaptiveIcon by state::asAdaptiveIcon
    var zoomLevel by state::zoomLevel
    var selectedImagePath by state::selectedImagePath
    var hadSelectedImage by remember { mutableStateOf(selectedImagePath != null) }
    var savedImages by remember { mutableStateOf<List<File>>(emptyList()) }
    var uploadedImage by remember { mutableStateOf<Bitmap?>(null) }
    var mask by remember { mutableStateOf<Bitmap?>(null) }
    var loadedPath by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf(GallerySelection()) }
    var isUploading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val uploadErrorMessage = stringResource(R.string.uploadImageError)
    val deletedMessage = stringResource(R.string.imagesDeleted)
    val undoLabel = stringResource(R.string.undo)

    fun deleteMarked() {
        val toDelete = savedImages.filter { it.absolutePath in selection.paths }
        selection = GallerySelection()
        if (toDelete.isEmpty()) return
        snackbarHostState.currentSnackbarData?.dismiss()
        scope.launch {
            val trashed = library.moveToTrash(toDelete)
            if (trashed.isEmpty()) {
                toaster.show(uploadErrorMessage)
                return@launch
            }
            val movedFiles = trashed.map { it.original }.toSet()
            savedImages = savedImages - movedFiles
            if (movedFiles.any { it.absolutePath == selectedImagePath }) {
                state.selectGallery(null)
            }
            if (trashed.size != toDelete.size) toaster.show(uploadErrorMessage)

            val result = snackbarHostState.showSnackbar(
                message = String.format(deletedMessage, trashed.size),
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                library.restore(trashed)
                savedImages = library.list()
            } else {
                library.permanentlyDelete(trashed)
            }
        }
    }

    LaunchedEffect(Unit) {
        savedImages = library.load()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            isUploading = true
            try {
                val imported = library.import(uris)
                savedImages = library.list()
                imported.added.firstOrNull()?.absolutePath?.let(state::selectGallery)
                if (imported.anyFailed) toaster.show(uploadErrorMessage)
            } finally {
                isUploading = false
            }
        }
    }

    LaunchedEffect(selectedImagePath, state.ownedImage) {
        val path = selectedImagePath
        if (path == null) {
            uploadedImage = null
            mask = null
            loadedPath = null
            if (hadSelectedImage) onChange(null, null)
            hadSelectedImage = false
            return@LaunchedEffect
        }
        hadSelectedImage = true
        val bitmap = state.ownedImage ?: library.decode(path)
        if (bitmap == null) {
            uploadedImage = null
            mask = null
            loadedPath = null
            selectedImagePath = null
            onChange(null, null)
            toaster.show(uploadErrorMessage)
        } else {
            val squared = squareBitmap(bitmap)
            uploadedImage = squared
            mask = createMask(squared)
            loadedPath = path
        }
    }

    BackHandler(enabled = selection.active) {
        selection = GallerySelection()
    }

    Box(Modifier.fillMaxSize()) {
        val previewSelection = state.selection()
        val previewOwnedImage = state.ownedImage
        UploadGalleryContent(
            savedImages = savedImages,
            uploadedImage = uploadedImage.takeIf { selectedImagePath != null && loadedPath == selectedImagePath },
            mask = mask.takeIf { selectedImagePath != null && loadedPath == selectedImagePath },
            asAdaptiveIcon = asAdaptiveIcon,
            zoomLevel = zoomLevel,
            selectedImagePath = selectedImagePath,
            selection = selection,
            onAdaptiveIconChange = {
                asAdaptiveIcon = it
                zoomLevel = 1f
            },
            onZoomChange = { zoomLevel = it },
            onSelectedImageChange = state::selectGallery,
            onSelectionChange = { selection = it },
            loadThumbnail = library::thumbnail,
            onChange = { icon, path ->
                if (path != null && previewSelection == state.selection() && path == previewSelection.path &&
                    previewOwnedImage === state.ownedImage
                ) {
                    if (state.ownedImage == null || state.publishedSelection != previewSelection) onChange(icon, path)
                }
            }
        )
        UploadGalleryActions(
            savedImages = savedImages,
            selection = selection,
            onSelectionChange = { selection = it },
            onDelete = ::deleteMarked,
            onAdd = { launcher.launch(MIME_TYPE_IMAGE) },
            modifier = Modifier.align(
                if (selection.active) Alignment.BottomCenter else Alignment.BottomEnd
            )
        )
        if (isUploading) UploadLoadingOverlay()
    }
}
