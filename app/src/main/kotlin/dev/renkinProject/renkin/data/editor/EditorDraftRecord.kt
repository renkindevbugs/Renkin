package dev.renkinProject.renkin.data.editor

/** Durable comparison data; a restored draft must acquire a new live profile-session token. */
internal data class EditorDraftOwner(
    val profileId: Long,
    val packageName: String,
    val activityName: String,
    val profileFingerprint: String,
    val originalIconFingerprint: String
)

/** Compare-and-set ownership for disk writes, independent of the lifetime of a provider. */
internal data class EditorDraftCheckpoint(val storageGeneration: String, val draftId: String?)

internal data class EditorDraftArtifact(val name: String, val bytes: Long, val sha256: String)

internal data class EditorDraftRecord(
    val id: String,
    val savedAt: Long,
    val owner: EditorDraftOwner,
    val artifacts: List<EditorDraftArtifact>
)

internal data class EditorDraftCandidate(val checkpoint: EditorDraftCheckpoint, val record: EditorDraftRecord)

internal data class EditorDraftCatalog(val storageGeneration: String, val draft: EditorDraftRecord?) {
    val checkpoint get() = EditorDraftCheckpoint(storageGeneration, draft?.id)
}
