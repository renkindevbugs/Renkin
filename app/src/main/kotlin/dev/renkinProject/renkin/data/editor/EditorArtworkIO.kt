package dev.renkinProject.renkin.data.editor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal class EditorArtworkWriter(private val checkActive: () -> Unit = {}) {
    fun ensureActive() = checkActive()
    private val bytes = object : ByteArrayOutputStream() {
        override fun write(value: Int) {
            require(count.toLong() < EditorDraftCodec.MAX_ARTIFACT_BYTES) { "Editor artwork is too large" }
            super.write(value)
        }
        override fun write(value: ByteArray, offset: Int, length: Int) {
            require(count.toLong() + length <= EditorDraftCodec.MAX_ARTIFACT_BYTES) { "Editor artwork is too large" }
            super.write(value, offset, length)
        }
    }
    val output = DataOutputStream(bytes)
    val budget = EditorArtworkBudget(checkActive)
    fun boolean(value: Boolean) = output.writeByte(if (value) 1 else 0)
    fun number(value: Float) { checkActive(); require(value.isFinite()) { "Non-finite editor artwork" }; output.writeFloat(value) }
    fun text(value: String) {
        require(value.length <= EditorControlsCodec.MAX_TEXT_BYTES && Charsets.UTF_8.newEncoder().canEncode(value)) { "Invalid artwork text" }
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size <= EditorControlsCodec.MAX_TEXT_BYTES) { "Artwork text exceeds limit" }
        output.writeInt(encoded.size); output.write(encoded)
    }
    fun optionalText(value: String?) { boolean(value != null); value?.let(::text) }
    fun blob(value: EditorArtworkBytes) { budget.bytes(value.size); output.writeInt(value.size); output.write(value.copyBytes()) }
    fun optionalBlob(value: EditorArtworkBytes?) { boolean(value != null); value?.let(::blob) }
    fun result(): ByteArray = bytes.toByteArray()
}

internal class EditorArtworkReader(encoded: ByteArray, private val checkActive: () -> Unit = {}) {
    fun ensureActive() = checkActive()
    init { require(encoded.size.toLong() in 1..EditorDraftCodec.MAX_ARTIFACT_BYTES) { "Invalid artwork document size" } }
    val input = DataInputStream(ByteArrayInputStream(encoded))
    val budget = EditorArtworkBudget(checkActive)
    fun boolean(): Boolean = when (input.readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("Invalid artwork boolean")
    }
    fun number(): Float = input.readFloat().also { checkActive(); require(it.isFinite()) { "Non-finite editor artwork" } }
    fun count(limit: Int, minimumBytes: Int = 1): Int = input.readInt().also {
        require(it in 0..limit && it <= input.available() / minimumBytes) { "Invalid artwork count" }
    }
    fun text(): String {
        val bytes = ByteArray(count(EditorControlsCodec.MAX_TEXT_BYTES)); input.readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    fun optionalText(): String? = if (boolean()) text() else null
    fun blob(): EditorArtworkBytes {
        val size = count(EditorDraftCodec.MAX_ARTIFACT_BYTES.toInt()); budget.bytes(size)
        val bytes = ByteArray(size); input.readFully(bytes); return EditorArtworkBytes(bytes)
    }
    fun optionalBlob(): EditorArtworkBytes? = if (boolean()) blob() else null
    fun finished() { require(input.read() == -1) { "Trailing artwork content" } }
}
