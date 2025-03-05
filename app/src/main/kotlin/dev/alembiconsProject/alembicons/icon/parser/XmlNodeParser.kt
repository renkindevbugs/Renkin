package dev.alembiconsProject.alembicons.icon.parser

import android.content.res.Resources
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import dev.alembiconsProject.alembicons.drawable.BitmapIconDrawable
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.drawable.ImageVectorDrawable
import dev.alembiconsProject.alembicons.drawable.InsetIconDrawable
import dev.alembiconsProject.alembicons.extension.bitmapFromBase64
import dev.alembiconsProject.alembicons.vector.VectorParser
import dev.alembiconsProject.alembicons.xml.XmlNode

class XmlNodeParser(val resources: Resources, private val defaultColor: Color = Color.Unspecified) {
    private fun parse(node: XmlNode): IconPackDrawable? {
        if (node.containsTag("vector")) {
            val vector = VectorParser.parse(resources, node, defaultColor)

            if (vector != null) {
                return ImageVectorDrawable(vector)
            }
        }

        if (node.containsTag("inset")) {
            return parseInset(node)
        }

        return null
    }

    private fun parseInset(node: XmlNode): IconPackDrawable? {
        val inset = node.findFirstTag("inset") ?: return null

        val insetBottom = parseInsetValue(inset.getAttributeValue("insetBottom") ?: "")
        val insetLeft = parseInsetValue(inset.getAttributeValue("insetLeft") ?: "")
        val insetRight = parseInsetValue(inset.getAttributeValue("insetRight") ?: "")
        val insetTop = parseInsetValue(inset.getAttributeValue("insetTop") ?: "")

        val defaultDims = Rect(insetLeft.toInt(), insetTop.toInt(), insetRight.toInt(), insetBottom.toInt())
        val fractions = if (insetBottom < 1f) {
            RectF(insetLeft, insetTop, insetRight, insetBottom)
        } else {
            RectF(-1f, -1f, -1f, -1f)
        }

        if (inset.containsAttribute("drawable")) {
            val drawable = inset.getAttributeValue("drawable") ?: return null
            val bitmap = bitmapFromBase64(drawable)

            val dims = if (insetBottom < 1f) {
                Rect((insetLeft * bitmap.width).toInt()
                    , (insetTop * bitmap.height).toInt()
                    , (insetRight * bitmap.width).toInt()
                    , (insetBottom * bitmap.height).toInt())
            } else {
                defaultDims
            }

            return InsetIconDrawable(BitmapIconDrawable(bitmap), dims, fractions)
        }

        if (inset.containsTag("vector")) {
            val vector = VectorParser.parse(resources, inset, defaultColor)

            if (vector != null) {
                val dims = if (insetBottom < 1f) {
                    Rect((insetLeft * vector.viewportWidth).toInt()
                        , (insetTop * vector.viewportHeight).toInt()
                        , (insetRight * vector.viewportWidth).toInt()
                        , (insetBottom * vector.viewportHeight).toInt())
                } else {
                    defaultDims
                }

                return InsetIconDrawable(ImageVectorDrawable(vector), dims, fractions)
            }
        }

        return null
    }

    private fun parseInsetValue(value: String): Float {
        if (value.contains('%')) {
            val perc = value.replace("%", "").toFloatOrNull() ?: return 1f
            return perc / 100
        } else {
            return value.replace("dp", "").toFloatOrNull() ?: 1f
        }
    }

    companion object {
        fun parse(resources: Resources, node: XmlNode, defaultColor: Color = Color.Unspecified): IconPackDrawable? {
            val parser = XmlNodeParser(resources, defaultColor)
            return parser.parse(node)
        }
    }
}