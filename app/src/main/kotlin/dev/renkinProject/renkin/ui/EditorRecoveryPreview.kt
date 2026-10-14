package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.annotation.WorkerThread
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException

internal data class EditorRecoveryPreview(val applicationIcon: Bitmap?, val savedRender: Bitmap?)

@WorkerThread
internal fun prepareEditorRecoveryPreview(draft: PreparedEditorRecovery): EditorRecoveryPreview? = try {
    val original = draft.baseline.application.icon
    val applicationIcon = (original.constantState?.newDrawable()?.mutate() ?: original)
        .toSafeBitmapOrNull(128, 128)?.detachedPreview()
    val renderedSlot = draft.bound.capture.snapshot.rendered
    val savedRender = renderedSlot?.let { draft.bound.artwork.getValue(it).previewBitmap(512).detachedPreview() }
    EditorRecoveryPreview(applicationIcon, savedRender)
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Log.error("EditorDraftRecovery", "Saved preview could not be prepared; recovery remains available", error)
    null
} catch (error: OutOfMemoryError) {
    Log.error("EditorDraftRecovery", "Saved preview exceeded available memory; recovery remains available", error)
    null
}

// A disposed preparation may recycle its pixels before Compose releases the previous draw frame.
// These small immutable copies belong to their UI consumers and are never manually recycled.
private fun Bitmap.detachedPreview(): Bitmap = requireNotNull(copy(Bitmap.Config.ARGB_8888, false)) {
    "Could not copy the saved editor preview"
}
