package dev.renkinProject.renkin.ui

import androidx.annotation.MainThread
import dev.renkinProject.renkin.data.editor.EditorControlsSnapshot
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable

internal class EditorCapturePendingException : IllegalStateException("Editor capture is pending or superseded")

// References identify source publications only. Artwork is copied once, at explicit capture.
internal data class EditorRenderWitness(
    val controls: EditorControlsSnapshot,
    val origin: IconOrigin,
    val selections: List<ResourceDrawable>,
    val upload: UploadEditorState.Selection,
    val vector: VectorEditState.Inputs,
    val onlinePath: String?,
    val replay: EditorModifierReplay?,
    val publishedSource: IconPackDrawable?,
    val vectorSeed: VectorEditState.Inputs?
)

internal fun EditorSession.renderSource(): IconPackDrawable? = when (draft.origin) {
    IconOrigin.CREATE -> restoredIconSource ?: application.baseIcon ?: application.createdIcon
    IconOrigin.UPLOAD -> draft.uploadBase
    IconOrigin.VECTOR -> draft.vectorIcon
}

@MainThread
internal fun EditorSession.captureRenderWitness(): EditorRenderWitness = EditorRenderWitness(
    captureControls(), draft.origin, customIconList.toList(), upload.selection(),
    vectorEditState.inputs(), onlineImagePath, modifierReplay, renderSource(), vectorEditState.seedForDraft()
)

@MainThread
internal fun EditorSession.renderWitnessOrNull(): EditorRenderWitness? = try {
    captureRenderWitness()
} catch (_: IllegalArgumentException) {
    // Unsupported recovery values must not disable the live rendering/Apply pipeline.
    null
}

@MainThread
internal fun EditorSession.requirePublishedSources(witness: EditorRenderWitness) {
    if (restoringModifiers || draft.replayRenderFailed) throw EditorCapturePendingException()
    if (witness.origin == IconOrigin.UPLOAD) {
        if (upload.publishedSelection != witness.upload) throw EditorCapturePendingException()
    }
    if (witness.origin == IconOrigin.VECTOR || vectorEditState.hasChangesFromSeed) {
        if (vectorEditState.publishedInputs != witness.vector) throw EditorCapturePendingException()
    }
}

@MainThread
internal fun EditorSession.canConfirmDraft(witness: EditorRenderWitness?): Boolean =
    !restoringModifiers && draft.canConfirm(witness) &&
        (draft.origin != IconOrigin.UPLOAD || upload.publishedSelection == upload.selection()) &&
        ((draft.origin != IconOrigin.VECTOR && !vectorEditState.hasChangesFromSeed) ||
            vectorEditState.publishedInputs == vectorEditState.inputs())
