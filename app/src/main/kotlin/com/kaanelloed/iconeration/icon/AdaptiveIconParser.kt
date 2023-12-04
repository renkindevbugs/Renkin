package com.kaanelloed.iconeration.icon

import android.content.res.Resources
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.VectorDrawable
import androidx.core.content.res.ResourcesCompat
import com.kaanelloed.iconeration.vector.VectorParser
import com.kaanelloed.iconeration.xml.XmlNode
import com.kaanelloed.iconeration.xml.XmlParser.Companion.toXmlNode

class AdaptiveIconParser(private val resources: Resources) {
    private fun parse(node: XmlNode): AdaptiveIcon? {
        val icon = node.findFirstTag("adaptive-icon")!!
        var foreground: BaseIcon? = null
        var background: BaseIcon? = null
        var monochrome: BaseIcon? = null

        for (child in icon.children) {
            when (child.name) {
                "foreground" -> foreground = parseChild(child)
                "background" -> background = parseChild(child)
                "monochrome" -> monochrome = parseChild(child)
            }
        }

        if (foreground != null && background != null) {
            return AdaptiveIcon(foreground, background, monochrome)
        }

        return null
    }

    private fun parseChild(node: XmlNode): BaseIcon {
        if (node.containsChildTag("inset")) {
            return parseInset(node.findFirstChildTag("inset")!!)
        }

        //Foreground in other file
        if (node.containsAttribute("drawable")) {
            val drawableValue = node.getAttributeValue("drawable")

            if (drawableValue != null) {
                val id = drawableValue.substring(1).toInt()
                val drawable = ResourcesCompat.getDrawable(resources, id, null)!!

                if (drawable is VectorDrawable) {
                    return parseVector(resources.getXml(id).toXmlNode())
                }

                if (drawable is BitmapDrawable) {
                    return BitmapIcon(drawable.bitmap)
                }
            }
        }

        //Foreground in the same file
        if (node.containsChildTag("vector")) {
            return parseVector(node.findFirstChildTag("vector")!!)
        }

        return EmptyIcon()
    }

    private fun parseInset(node: XmlNode): InsetIcon {
        val inset = 0F //TODO

        return InsetIcon(inset, parseChild(node))
    }

    private fun parseVector(node: XmlNode): VectorIcon {
        return VectorIcon(VectorParser.parse(resources, node)!!)
    }

    companion object {
        fun parse(resources: Resources, node: XmlNode): AdaptiveIcon? {
            val adaptiveIconParser = AdaptiveIconParser(resources)
            return adaptiveIconParser.parse(node)
        }
    }
}