package dev.renkinProject.renkin.data.editor

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal class EditorArtworkWriter(private val checkActive: () -> Unit = {}) {
    fun ensureActive() = checkActive()
    private val bytes = CappedByteArrayOutputStream(EditorDraftCodec.MAX_ARTIFACT_BYTES, "Editor artwork is too large")
    val output = DataOutputStream(bytes)
    val budget = EditorArtworkBudget(checkActive)
    fun boolean(value: Boolean) = output.writeByte(if (value) 1 else 0)
    fun number(value: Float) { checkActive(); require(value.isFinite()) { "Non-finite editor artwork" }; output.writeFloat(value) }
    fun text(value: String, limit: Int = EditorControlsCodec.MAX_TEXT_BYTES) =
        output.writeBoundedUtf8(value, limit, "Invalid artwork text", "Artwork text exceeds limit")
    fun optionalText(value: String?, limit: Int = EditorControlsCodec.MAX_TEXT_BYTES) { boolean(value != null); value?.let { text(it, limit) } }
    fun blob(value: EditorArtworkBytes) { budget.bytes(value.size); output.writeInt(value.size); output.write(value.copyBytes()) }
    fun optionalBlob(value: EditorArtworkBytes?) { boolean(value != null); value?.let(::blob) }
    fun result(): ByteArray = bytes.toByteArray()
}

internal class EditorArtworkReader(encoded: ByteArray, private val checkActive: () -> Unit = {}) {
    fun ensureActive() = checkActive()
    init { require(encoded.size.toLong() in 1..EditorDraftCodec.MAX_ARTIFACT_BYTES) { "Invalid artwork document size" } }
    val input = DataInputStream(ByteArrayInputStream(encoded))
    val budget = EditorArtworkBudget(checkActive)
    fun boolean(): Boolean = input.readStrictBoolean("Invalid artwork boolean")
    fun number(): Float = input.readFloat().also { checkActive(); require(it.isFinite()) { "Non-finite editor artwork" } }
    fun count(limit: Int, minimumBytes: Int = 1): Int = input.readInt().also {
        require(it in 0..limit && it <= input.available() / minimumBytes) { "Invalid artwork count" }
    }
    fun text(limit: Int = EditorControlsCodec.MAX_TEXT_BYTES): String {
        val bytes = ByteArray(count(limit)); input.readFully(bytes)
        return strictUtf8(bytes)
    }
    fun optionalText(limit: Int = EditorControlsCodec.MAX_TEXT_BYTES): String? = if (boolean()) text(limit) else null
    fun blob(): EditorArtworkBytes {
        val size = count(EditorDraftCodec.MAX_ARTIFACT_BYTES.toInt()); budget.bytes(size)
        val bytes = ByteArray(size); input.readFully(bytes); return EditorArtworkBytes(bytes)
    }
    fun optionalBlob(): EditorArtworkBytes? = if (boolean()) blob() else null
    fun finished() { require(input.read() == -1) { "Trailing artwork content" } }
}
