package dev.renkinProject.renkin.extension

private const val HEX_DIGITS = "0123456789abcdef"

/** Lowercase hexadecimal text, two digits per byte: the form every stored digest and key uses. */
fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    for (byte in this@toLowerHex) {
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}
