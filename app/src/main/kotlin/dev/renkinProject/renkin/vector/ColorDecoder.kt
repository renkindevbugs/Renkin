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

        /** Parses SVG/CSS solid colours without applying Android's different 8-digit hex order. */
        fun decodeSvgCss(value: String): Color {
            val raw = value.trim().lowercase()
            if (raw == "transparent") return Color.Transparent
            if (raw.startsWith("#")) return decodeSvgHex(raw)
            if (raw.startsWith("rgb(")) return decodeSvgRgb(raw, hasAlpha = false)
            if (raw.startsWith("rgba(")) return decodeSvgRgb(raw, hasAlpha = true)
            return runCatching { Color(android.graphics.Color.parseColor(raw)) }
                .getOrDefault(Color.Unspecified)
        }

        private fun decodeSvgHex(value: String): Color = runCatching {
            val digits = value.drop(1)
            fun hex(pair: String) = pair.toInt(16) / 255f
            when (digits.length) {
                3 -> Color(hex("${digits[0]}${digits[0]}"), hex("${digits[1]}${digits[1]}"), hex("${digits[2]}${digits[2]}"))
                4 -> Color(
                    hex("${digits[0]}${digits[0]}"),
                    hex("${digits[1]}${digits[1]}"),
                    hex("${digits[2]}${digits[2]}"),
                    hex("${digits[3]}${digits[3]}")
                )
                6 -> Color(hex(digits.substring(0, 2)), hex(digits.substring(2, 4)), hex(digits.substring(4, 6)))
                8 -> Color(
                    hex(digits.substring(0, 2)),
                    hex(digits.substring(2, 4)),
                    hex(digits.substring(4, 6)),
                    hex(digits.substring(6, 8))
                )
                else -> Color.Unspecified
            }
        }.getOrDefault(Color.Unspecified)

        private fun decodeSvgRgb(value: String, hasAlpha: Boolean): Color = runCatching {
            val components = value.substringAfter('(').substringBeforeLast(')')
                .split(',').map(String::trim)
            if (components.size != if (hasAlpha) 4 else 3) return Color.Unspecified
            fun channel(component: String): Float = if (component.endsWith('%')) {
                component.dropLast(1).toFloat().div(100f)
            } else {
                component.toFloat().div(255f)
            }.coerceIn(0f, 1f)
            fun alpha(component: String): Float = if (component.endsWith('%')) {
                component.dropLast(1).toFloat().div(100f)
            } else {
                component.toFloat()
            }.coerceIn(0f, 1f)
            Color(
                channel(components[0]),
                channel(components[1]),
                channel(components[2]),
                if (hasAlpha) alpha(components[3]) else 1f
            )
        }.getOrDefault(Color.Unspecified)
    }
}
