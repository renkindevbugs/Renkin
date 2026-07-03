package dev.renkinProject.renkin.vector

import android.content.res.Resources
import android.content.res.Resources.Theme
import androidx.compose.ui.graphics.Color
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.isDigitsOnly
import dev.renkinProject.renkin.extension.toColor

class ColorDecoder(val resources: Resources, private val defaultColor: Color = Color.Unspecified) {
    private fun decode(value: String): Color {
        if (value.startsWith("#")) {
            return parseRaw(value)
        }

        if (value.startsWith("@")) {
            return parseResource(value)
        }

        // "rgba" must be checked before "rgb" — it also starts with "rgb", so the broader
        // check would otherwise swallow it and mis-parse the alpha channel.
        if (value.startsWith("rgba")) {
            return parseRgb(value, hasAlpha = true)
        }

        if (value.startsWith("rgb")) {
            return parseRgb(value, hasAlpha = false)
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

    /**
     * Parses an `rgb(r,g,b)` / `rgba(r,g,b,a)` colour. Reading the components between the
     * parentheses (rather than a fixed offset) handles both prefixes without an off-by-one.
     */
    private fun parseRgb(value: String, hasAlpha: Boolean): Color {
        val components = value.substringAfter('(').substringBefore(')')
            .split(",")
            .map { it.trim().toFloat() }

        return if (hasAlpha) {
            Color(components[0], components[1], components[2], components[3])
        } else {
            Color(components[0], components[1], components[2])
        }
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