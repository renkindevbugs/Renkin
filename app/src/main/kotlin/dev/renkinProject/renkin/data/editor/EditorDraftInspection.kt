package dev.renkinProject.renkin.data.editor

/** A process-local confirmation bound to one unreadable metadata observation, never saved state. */
internal class EditorDraftResetLease internal constructor(
    internal val storeOwner: Any,
    internal val writeVersion: Long,
    internal val metadataFingerprint: String
)

internal sealed interface EditorDraftInspection {
    data class Readable(
        val checkpoint: EditorDraftCheckpoint,
        val owner: EditorDraftOwner? = null
    ) : EditorDraftInspection
    data class Unreadable(val cause: Exception, val resetLease: EditorDraftResetLease?) : EditorDraftInspection
}
