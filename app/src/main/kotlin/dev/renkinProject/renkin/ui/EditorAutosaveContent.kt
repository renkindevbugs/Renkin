package dev.renkinProject.renkin.ui

import dev.renkinProject.renkin.data.editor.EditorControlsSnapshot
import dev.renkinProject.renkin.data.editor.EditorVectorControls
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable

// Autosave compares edits separately from the full witness used for coherent capture and recovery.
internal data class EditorAutosaveContent(
    val controls: EditorControlsSnapshot,
    val origin: IconOrigin,
    val selections: List<ResourceDrawable>,
    val upload: UploadEditorState.Selection,
    val vector: VectorEditState.Inputs?,
    val onlinePath: String?,
    val replay: EditorModifierReplay?,
    val publishedSource: IconPackDrawable?
)

private val UNEDITED_VECTOR_CONTROLS = EditorVectorControls(false, 1f, true, null, null, false)

internal fun EditorRenderWitness.autosaveContent(): EditorAutosaveContent {
    val uninitializedVector = !controls.vector.initialized && vectorSeed == null && vector.entries.isEmpty() &&
        vector.thickness == 1f && vector.automaticallyCenter && vector.viewport == null && vector.sourceUrl == null
    val uneditedVector = uninitializedVector || vector == vectorSeed
    return EditorAutosaveContent(
        controls.copy(
            selectedTab = 0,
            create = controls.create.copy(browsingIconPacks = false),
            vector = if (uneditedVector) UNEDITED_VECTOR_CONTROLS else controls.vector.copy(initialized = false)
        ),
        origin, selections, upload, vector.takeUnless { uneditedVector }, onlinePath, replay, publishedSource
    )
}
