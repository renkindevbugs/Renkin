package dev.renkinProject.renkin.data.editor

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A byte buffer that refuses to grow past [capacity], so a corrupt draft cannot exhaust memory. */
internal class CappedByteArrayOutputStream(
    private val capacity: Long,
    private val tooLargeMessage: String
) : ByteArrayOutputStream() {
    override fun write(value: Int) {
        require(count.toLong() < capacity) { tooLargeMessage }
        super.write(value)
    }

    override fun write(value: ByteArray, offset: Int, length: Int) {
        require(count.toLong() + length <= capacity) { tooLargeMessage }
        super.write(value, offset, length)
    }
}

/** Length-prefixed UTF-8: the only text encoding editor draft documents use. */
internal fun DataOutputStream.writeBoundedUtf8(value: String, limit: Int, invalidMessage: String, tooLargeMessage: String) {
    require(value.length <= limit && Charsets.UTF_8.newEncoder().canEncode(value)) { invalidMessage }
    val encoded = value.toByteArray(Charsets.UTF_8)
    require(encoded.size <= limit) { tooLargeMessage }
    writeInt(encoded.size)
    write(encoded)
}

internal fun DataInputStream.readStrictBoolean(invalidMessage: String): Boolean = when (readUnsignedByte()) {
    0 -> false
    1 -> true
    else -> throw IllegalArgumentException(invalidMessage)
}

/** Decodes stored text, rejecting malformed UTF-8 instead of silently replacing it. */
internal fun strictUtf8(encoded: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(encoded)).toString()
