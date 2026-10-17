package dev.renkinProject.renkin.icon.parser

import dev.renkinProject.renkin.icon.creator.MATERIAL_YOU_STROKE_SCALE_RANGE
import android.graphics.BitmapFactory
import android.util.Base64
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.drawable.MaterialYouPackEditState
import dev.renkinProject.renkin.icon.creator.decodeColorizerStyle
import dev.renkinProject.renkin.icon.creator.encodeColorizerStyle
import dev.renkinProject.renkin.xml.XmlNode
import dev.renkinProject.renkin.xml.file.XmlMemoryFile

internal object AdaptiveIconPayload {
    const val TAG = "renkin-adaptive-icon"
    private const val VERSION = "2"

    fun encode(icon: AdaptiveIconPackDrawable): String {
        val file = object : XmlMemoryFile() {
            init {
                initialize()
                startTag(TAG)
                attribute("version", VERSION)
                layer("foreground", icon.foregroundPng)
                layer("background", icon.backgroundPng)
                icon.monochromePng?.let { layer("monochrome", it) }
                icon.materialYouEditState?.let { state ->
                    startTag("material-you")
                    attribute("scheme", state.selectedScheme.toString())
                    attribute("foreground", encodeColorizerStyle(state.customForeground))
                    attribute("background", encodeColorizerStyle(state.customBackground))
                    attribute("stroke-scale", state.strokeScale.toString())
                    icon.originalForegroundPng?.let { layer("original-foreground", it) }
                    icon.originalBackgroundPng?.let { layer("original-background", it) }
                    endTag("material-you")
                }
                endTag(TAG)
            }

            private fun layer(name: String, png: ByteArray) {
                startTag(name)
                attribute("png", Base64.encodeToString(png, Base64.NO_WRAP))
                endTag(name)
            }
        }
        return Base64.encodeToString(file.readAndClose(), Base64.NO_WRAP)
    }

    fun decode(node: XmlNode): AdaptiveIconPackDrawable {
        val version = requireNotNull(node.getAttributeValue("version"))
        require(version == "1" || version == VERSION) { "Unsupported adaptive icon payload" }
        require(node.children.map { it.name }.toSet().size == node.children.size)
        require(node.children.all { it.name in setOf("foreground", "background", "monochrome", "material-you") })
        fun layer(parent: XmlNode, name: String): ByteArray {
            val child = requireNotNull(parent.children.singleOrNull { it.name == name })
            val png = Base64.decode(requireNotNull(child.getAttributeValue("png")), Base64.NO_WRAP)
            require(png.size >= PNG_SIGNATURE.size && png.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE))
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, options)
            require(options.outWidth == AdaptiveIconPackDrawable.LAYER_SIZE && options.outHeight == options.outWidth)
            return png
        }
        val materialNode = node.children.singleOrNull { it.name == "material-you" }
        val materialState = materialNode?.let {
            require(it.children.map { child -> child.name }.toSet().size == it.children.size)
            require(it.children.all { child ->
                child.name in setOf("original-foreground", "original-background")
            })
            val selectedScheme = requireNotNull(it.getAttributeValue("scheme")).toInt()
            require(selectedScheme >= -1)
            val strokeScale = requireNotNull(it.getAttributeValue("stroke-scale")).toFloat()
            require(strokeScale.isFinite() && strokeScale in MATERIAL_YOU_STROKE_SCALE_RANGE)
            MaterialYouPackEditState(
                selectedScheme = selectedScheme,
                customForeground = requireNotNull(
                    decodeColorizerStyle(requireNotNull(it.getAttributeValue("foreground")))
                ),
                customBackground = requireNotNull(
                    decodeColorizerStyle(requireNotNull(it.getAttributeValue("background")))
                ),
                strokeScale = strokeScale
            )
        }
        return AdaptiveIconPackDrawable(
            layer(node, "foreground"),
            layer(node, "background"),
            if (node.children.any { it.name == "monochrome" }) layer(node, "monochrome") else null,
            materialState,
            materialNode?.takeIf { it.children.any { child -> child.name == "original-foreground" } }
                ?.let { layer(it, "original-foreground") },
            materialNode?.takeIf { it.children.any { child -> child.name == "original-background" } }
                ?.let { layer(it, "original-background") }
        )
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
}
