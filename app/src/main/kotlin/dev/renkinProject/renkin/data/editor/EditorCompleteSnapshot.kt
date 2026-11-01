package dev.renkinProject.renkin.data.editor

import dev.renkinProject.renkin.icon.creator.ModifierInputOrigin
import dev.renkinProject.renkin.icon.creator.ModifierInputPipeline

internal enum class EditorDraftOrigin { CREATE, UPLOAD, VECTOR }
internal data class EditorInputSnapshot(
    val artwork: String, val generation: EditorGenerationSnapshot, val baked: Boolean,
    val origin: ModifierInputOrigin, val pipeline: ModifierInputPipeline
)
internal data class EditorSourceSelection(val resourceId: Int, val pack: String, val artwork: String)
internal data class EditorResourceReference(
    val kind: EditorDraftResourceKind, val selection: String, val artifact: String, val sha256: String
)
internal data class EditorOriginalSnapshot(
    val appName: String, val originalName: String, val base: String?, val rendered: String?,
    val recipe: String?, val input: EditorInputSnapshot?, val calendar: EditorCalendarControls,
    val sourcePack: String?, val sourceUrl: String?, val fallback: Boolean,
    val refreshMade: Boolean, val custom: Boolean, val legacy: Boolean
)
internal data class EditorReplaySnapshot(
    val input: EditorInputSnapshot, val baseline: EditorGenerationSnapshot,
    val plate: dev.renkinProject.renkin.icon.creator.ColorizerStyle?
)

/** Contains neither live owners nor filesystem authority. All artwork references are local slots. */
internal data class EditorCompleteSnapshot(
    val controls: EditorControlsSnapshot, val vector: EditorVectorWorkingState,
    val serial: Long, val origin: EditorDraftOrigin, val initialized: Boolean, val canRestore: Boolean,
    val vectorBaked: Boolean, val restoredSourceBaked: Boolean, val modifierRestoreFailed: Boolean,
    val original: EditorOriginalSnapshot, val raw: String?, val rendered: String?, val recipe: String?,
    val requested: EditorGenerationSnapshot, val renderedOptions: EditorGenerationSnapshot?,
    val pipeline: ModifierInputPipeline, val rawBaked: Boolean, val input: EditorInputSnapshot?,
    val restoredSource: String?, val restoredExisting: EditorInputSnapshot?, val replay: EditorReplaySnapshot?,
    val selections: List<EditorSourceSelection>, val uploadPath: String?, val onlinePath: String?,
    val resources: List<EditorResourceReference>
)

internal data class EditorCompleteCapture(
    val snapshot: EditorCompleteSnapshot,
    val artwork: Map<String, EditorArtworkSnapshot>,
    val resources: Map<String, EditorDraftResource>
)
