package dev.renkinProject.renkin.ui

import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.data.editor.EditorDraftCheckpoint
import dev.renkinProject.renkin.data.editor.EditorDraftOwner

/** Preparation is not acceptance: the future attaching owner must repeat these comparisons. */
internal class PreparedEditorRecovery(
    val checkpoint: EditorDraftCheckpoint,
    val owner: EditorDraftOwner,
    val baseline: ApplicationProvider.EditorDraftBaselineSource,
    val editorGeneration: Long,
    val bound: BoundEditorRecovery
)

internal sealed interface EditorRecoveryPreparation {
    data class Ready(val draft: PreparedEditorRecovery) : EditorRecoveryPreparation
    data class NeedsProfile(val profileId: Long) : EditorRecoveryPreparation
    data object Empty : EditorRecoveryPreparation
    data object Superseded : EditorRecoveryPreparation
    data object Obsolete : EditorRecoveryPreparation
    data class Failed(val cause: Throwable) : EditorRecoveryPreparation
}
