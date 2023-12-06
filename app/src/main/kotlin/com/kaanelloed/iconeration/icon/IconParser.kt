package com.kaanelloed.iconeration.icon

import android.content.res.Resources
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import com.kaanelloed.iconeration.ApplicationManager.Companion.getXmlOrNull
import com.kaanelloed.iconeration.vector.VectorParser
import com.kaanelloed.iconeration.xml.XmlParser.Companion.toXmlNode

class IconParser(private val resources: Resources) {
    private fun parseDrawable(drawable: Drawable, drawableId: Int): BaseIcon {
        return when (drawable) {
            is AdaptiveIconDrawable -> parseAdaptiveIcon(drawableId)
            is BitmapDrawable -> BitmapIcon(drawable.bitmap)
            is VectorDrawable -> parseVectorIcon(drawableId)
            else -> EmptyIcon()
        }
    }

    private fun parseAdaptiveIcon(drawableId: Int): AdaptiveIcon {
        val parser = resources.getXmlOrNull(drawableId)!!
        return AdaptiveIconParser.parse(resources, parser.toXmlNode())!!
    }

    private fun parseVectorIcon(drawableId: Int): VectorIcon {
        val parser = resources.getXmlOrNull(drawableId)!!
        val vector = VectorParser.parse(resources, parser.toXmlNode())!!
        return VectorIcon(vector)
    }

    companion object {
        fun parseDrawable(resources: Resources, drawable: Drawable, drawableId: Int): BaseIcon {
            val parser = IconParser(resources)
            return parser.parseDrawable(drawable, drawableId)
        }
    }
}