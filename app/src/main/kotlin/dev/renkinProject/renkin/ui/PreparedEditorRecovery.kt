package dev.renkinProject.renkin.ui

import androidx.annotation.MainThread
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
) {
    private var reconstructed: EditorSession? = null
    private var disposed = false

    // The owner must validate this lease again before publishing or transferring the session.
    @MainThread
    internal fun sessionForAcceptance(): EditorSession {
        check(!disposed) { "Editor recovery preparation has been disposed" }
        return reconstructed ?: reconstructEditorSession(this).also { reconstructed = it }
    }

    // Call only after every offer/preview consumer has detached. No live session may own it yet.
    @MainThread
    internal fun disposeUnattached() {
        if (disposed) return
        disposed = true
        reconstructed = null
        bound.disposeUnattached()
    }
}

internal sealed interface EditorRecoveryPreparation {
    data class Ready(val draft: PreparedEditorRecovery) : EditorRecoveryPreparation
    data class NeedsProfile(val profileId: Long) : EditorRecoveryPreparation
    data object Empty : EditorRecoveryPreparation
    data object Superseded : EditorRecoveryPreparation
    data object Obsolete : EditorRecoveryPreparation
    data class Failed(val cause: Throwable) : EditorRecoveryPreparation
}
