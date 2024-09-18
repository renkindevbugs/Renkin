package com.kaanelloed.iconeration.icon.parser

import android.content.res.Resources
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import com.kaanelloed.iconeration.drawable.DrawableExtension.Companion.isAdaptiveIconDrawable
import com.kaanelloed.iconeration.icon.AdaptiveIcon
import com.kaanelloed.iconeration.icon.BaseIcon
import com.kaanelloed.iconeration.icon.BitmapIcon
import com.kaanelloed.iconeration.icon.EmptyIcon
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.packages.ApplicationManager.Companion.getXmlOrNull
import com.kaanelloed.iconeration.vector.VectorParser
import com.kaanelloed.iconeration.xml.XmlParser.Companion.toXmlNode

class IconParser(private val resources: Resources) {
    private fun parseDrawable(drawable: Drawable, drawableId: Int): BaseIcon {
        if (drawable.isAdaptiveIconDrawable()) {
            return parseAdaptiveIcon(drawableId) ?: EmptyIcon()
        }

        return when (drawable) {
            is BitmapDrawable -> BitmapIcon(drawable.bitmap)
            is VectorDrawable -> parseVectorIcon(drawableId) ?: EmptyIcon()
            else -> EmptyIcon()
        }
    }

    private fun parseAdaptiveIcon(drawableId: Int): AdaptiveIcon? {
        val parser = resources.getXmlOrNull(drawableId) ?: return null
        return AdaptiveIconParser.parse(resources, parser.toXmlNode())
    }

    private fun parseVectorIcon(drawableId: Int): VectorIcon? {
        val parser = resources.getXmlOrNull(drawableId) ?: return null
        val vector = VectorParser.parse(resources, parser.toXmlNode()) ?: return null
        return VectorIcon(vector)
    }

    companion object {
        fun parseDrawable(resources: Resources, drawable: Drawable, drawableId: Int): BaseIcon {
            val parser = IconParser(resources)
            return parser.parseDrawable(drawable, drawableId)
        }
    }
}