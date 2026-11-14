package dev.renkinProject.renkin.icon.parser

import android.graphics.Bitmap
import android.util.Base64
import dev.renkinProject.renkin.drawable.ThemedGlyphDrawable
import dev.renkinProject.renkin.extension.bitmapFromBase64
import dev.renkinProject.renkin.extension.toBase64
import dev.renkinProject.renkin.xml.XmlNode
import dev.renkinProject.renkin.xml.file.XmlMemoryFile

/** Stores a [ThemedGlyphDrawable] as its glyph mask, inside the regular XML icon payload. */
internal object ThemedGlyphPayload {
    const val TAG = "renkin-themed-glyph"
    private const val VERSION = "1"

    fun encode(icon: ThemedGlyphDrawable): String {
        val file = object : XmlMemoryFile() {
            init {
                initialize()
                startTag(TAG)
                attribute("version", VERSION)
                attribute("mask", icon.mask.toBase64(Bitmap.CompressFormat.PNG, 100))
                endTag(TAG)
            }
        }
        return Base64.encodeToString(file.readAndClose(), Base64.NO_WRAP)
    }

    fun decode(node: XmlNode): ThemedGlyphDrawable {
        require(node.getAttributeValue("version") == VERSION) { "Unsupported themed glyph payload" }
        val mask = bitmapFromBase64(requireNotNull(node.getAttributeValue("mask")))
        require(mask.width in 1..4096 && mask.height in 1..4096) { "Invalid themed glyph mask" }
        return ThemedGlyphDrawable(mask)
    }
}
