package dev.renkinProject.renkin.ui

import androidx.annotation.MainThread
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.editor.EditorDraftCheckpoint
import dev.renkinProject.renkin.data.editor.EditorDraftOwner

/** Owns native values until one validated VM publication transfers them to the live editor. */
internal class PreparedEditorRecovery(
    val checkpoint: EditorDraftCheckpoint,
    val owner: EditorDraftOwner,
    val baseline: ApplicationProvider.EditorDraftBaselineSource,
    val editorGeneration: Long,
    val bound: BoundEditorRecovery,
    internal val editorOwner: Any
) {
    private enum class Ownership { UNATTACHED, TRANSFERRED, DISPOSED }
    private var reconstructed: EditorSession? = null
    private var ownership = Ownership.UNATTACHED

    internal val canTransfer: Boolean
        @MainThread get() = ownership == Ownership.UNATTACHED

    // The owner must validate this lease again before publishing or transferring the session.
    @MainThread
    internal fun sessionForAcceptance(): EditorSession {
        check(canTransfer) { "Editor recovery preparation is no longer unattached" }
        return reconstructed ?: reconstructEditorSession(this).also { reconstructed = it }
    }

    @MainThread
    internal fun transferToEditor(session: EditorSession): Boolean {
        if (!canTransfer || reconstructed !== session) return false
        ownership = Ownership.TRANSFERRED
        reconstructed = null
        return true
    }

    // Call only after every offer/preview consumer has detached. No live session may own it yet.
    @MainThread
    internal fun disposeUnattached() {
        if (!canTransfer) return
        ownership = Ownership.DISPOSED
        reconstructed = null
        bound.disposeUnattached()
    }
}

internal data class EditorRecoveryAttachment(
    val sessionId: String,
    val packageName: String,
    val activityName: String,
    val owner: ProfileSessionToken,
    val themed: Boolean
)

internal sealed interface EditorRecoveryAcceptance {
    data class Accepted(val attachment: EditorRecoveryAttachment) : EditorRecoveryAcceptance
    data object Superseded : EditorRecoveryAcceptance
    data class Failed(val cause: Throwable) : EditorRecoveryAcceptance
}

internal sealed interface EditorRecoveryPreparation {
    data class Ready(val draft: PreparedEditorRecovery) : EditorRecoveryPreparation
    data class NeedsProfile(val profileId: Long) : EditorRecoveryPreparation
    data object Empty : EditorRecoveryPreparation
    data object Superseded : EditorRecoveryPreparation
    data object Obsolete : EditorRecoveryPreparation
    data class Failed(val cause: Throwable) : EditorRecoveryPreparation
}
