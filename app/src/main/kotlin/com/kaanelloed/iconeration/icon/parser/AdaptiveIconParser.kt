package com.kaanelloed.iconeration.icon.parser

import android.content.res.Resources
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.VectorDrawable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import com.kaanelloed.iconeration.icon.AdaptiveIcon
import com.kaanelloed.iconeration.icon.BaseIcon
import com.kaanelloed.iconeration.icon.BitmapIcon
import com.kaanelloed.iconeration.icon.EmptyIcon
import com.kaanelloed.iconeration.icon.InsetIcon
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.vector.VectorParser
import com.kaanelloed.iconeration.xml.XmlNode
import org.xmlpull.v1.XmlPullParserException

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

    private fun parseChild(node: XmlNode): BaseIcon? {
        if (node.containsChildTag("inset")) {
            return parseInset(node.findFirstChildTag("inset")!!)
        }

        //Foreground in other file
        if (node.containsAttribute("drawable")) {
            val drawableValue = node.getAttributeValue("drawable")

            if (drawableValue != null) {
                val id = drawableValue.substring(1).toInt()
                val drawable = ResourcesCompat.getDrawable(resources, id, null) ?: return null

                if (drawable is VectorDrawable) {
                    val vector = getVectorResource(resources, id) ?: return null

                    return VectorIcon(vector)
                    //return parseVector(resources.getXml(id).toXmlNode())
                }

                if (drawable is BitmapDrawable) {
                    return BitmapIcon(drawable.bitmap)
                }

                if (drawable is ColorDrawable) {
                    return BitmapIcon(drawable.toBitmap(108, 108))
                }
            }
        }

        //Foreground in the same file
        if (node.containsChildTag("vector")) {
            return parseVector(node.findFirstChildTag("vector")!!)
        }

        return null
    }

    private fun parseInset(node: XmlNode): InsetIcon? {
        val inset = 0F //TODO
        val icon = parseChild(node) ?: return null
        return InsetIcon(inset, icon)
    }

    private fun parseVector(node: XmlNode): VectorIcon? {
        val vector = VectorParser.parse(resources, node) ?: return null
        return VectorIcon(vector)
    }

    private fun getVectorResource(resources: Resources, id: Int): ImageVector? {
        return try {
            return ImageVector.vectorResource(null, resources, id)
        } catch (e: XmlPullParserException) {
            null
        }
    }

    companion object {
        fun parse(resources: Resources, node: XmlNode): AdaptiveIcon? {
            val adaptiveIconParser = AdaptiveIconParser(resources)
            return adaptiveIconParser.parse(node)
        }
    }
}