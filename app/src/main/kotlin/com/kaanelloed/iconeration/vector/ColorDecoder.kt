package com.kaanelloed.iconeration.vector

import android.content.res.Resources
import android.content.res.Resources.Theme
import androidx.compose.ui.graphics.Color
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.isDigitsOnly
import com.kaanelloed.iconeration.ui.toColor

class ColorDecoder(val resources: Resources, private val defaultColor: Color = Color.Unspecified) {
    private fun decode(value: String): Color {
        if (value.startsWith("#")) {
            return parseRaw(value)
        }

        if (value.startsWith("@")) {
            return parseResource(value)
        }

        if (value.startsWith("rgb")) {
            return parseRGB(value)
        }

        if (value.startsWith("rgba")) {
            return parseRBGA(value)
        }

        return defaultColor
    }

    private fun parseRaw(value: String): Color {
        if (value.length == 2) {
            val hex = "#" + value[1].toString().repeat(8)
            return hex.toColor()
        }

        if (value.length == 4) {
            val hex = "#FF" + value[1] + value[1] + value[2] + value[2] + value[3] + value[3]
            return hex.toColor()
        }

        if (value.length == 5) {
            val hex = "#" + value[1] + value[1] + value[2] + value[2] + value[3] + value[3] + value[4] + value[4]
            return hex.toColor()
        }

        return value.toColor()
    }

    private fun parseResource(value: String): Color {
        val id = value.substring(1)

        if (id.isDigitsOnly()) {
            return resources.getComposeColor(id.toInt(), null)
        }

        return defaultColor
    }

    private fun parseRGB(value: String): Color {
        val rgb = value.substring(4, value.length - 1)

        val elements = rgb.split(",")

        val red = elements[0].toFloat()
        val green = elements[1].toFloat()
        val blue = elements[2].toFloat()

        return Color(red, green, blue)
    }

    private fun parseRBGA(value: String): Color {
        val rgb = value.substring(4, value.length - 2)

        val elements = rgb.split(",")

        val red = elements[0].toFloat()
        val green = elements[1].toFloat()
        val blue = elements[2].toFloat()
        val alpha = elements[3].toFloat()

        return Color(red, green, blue, alpha)
    }

    private fun Resources.getComposeColor(id: Int, theme: Theme?): Color {
        val color = this.getColorOrNull(id, theme)

        return if (color == null) {
            defaultColor
        } else {
            Color(color)
        }
    }

    private fun Resources.getColorOrNull(id: Int, theme: Theme?): Int? {
        return try {
            ResourcesCompat.getColor(this, id, theme)
        } catch (e: Resources.NotFoundException) {
            null
        }
    }

    companion object {
        fun decode(resources: Resources, value: String, defaultColor: Color = Color.Unspecified): Color {
            val decoder = ColorDecoder(resources, defaultColor)
            return decoder.decode(value)
        }
    }
}