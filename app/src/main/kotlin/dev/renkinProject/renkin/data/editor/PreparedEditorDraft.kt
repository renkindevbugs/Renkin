package dev.renkinProject.renkin.data.editor

import dev.renkinProject.renkin.apk.ApplicationProvider

// This lease is process-local. Its session and baseline must never enter the durable payload.
internal class PreparedEditorDraft(
    val checkpoint: EditorDraftCheckpoint,
    val owner: EditorDraftOwner,
    val baseline: ApplicationProvider.EditorDraftBaselineSource,
    val frameSerial: Long,
    private val payloads: Map<String, ByteArray>
) {
    fun copyPayloads(): Map<String, ByteArray> = payloads.mapValues { it.value.copyOf() }
}

internal sealed interface EditorCaptureResult {
    data class Ready(val draft: PreparedEditorDraft) : EditorCaptureResult
    data object Superseded : EditorCaptureResult
    data class Failed(val cause: Throwable) : EditorCaptureResult
}
