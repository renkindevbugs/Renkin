package dev.renkinProject.renkin.vector.brush

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.toArgb

/**
 * Stored-vector encoding of a [VectorGradientBrush]: one `<renkin-gradient>` child of `<path>` per
 * painted slot, every value an attribute. No grandchildren on purpose: releases before v2026.11.01
 * rebuild the XML tree wrongly when a closing run drops more than one level and would drop later
 * paths. Old builds ignore the element and paint the path's solid fallback colour attribute.
 */
object GradientXml {
    const val TAG = "renkin-gradient"
    const val TARGET_FILL = "fill"
    const val TARGET_STROKE = "stroke"

    /** Attributes in a fixed order with `Float.toString`, so re-export stays byte-identical. */
    fun attributes(target: String, brush: VectorGradientBrush): List<Pair<String, String>> = listOf(
        "target" to target,
        "type" to if (brush.type == GradientType.RADIAL) "radial" else "linear",
        "x0" to brush.x0.toString(),
        "y0" to brush.y0.toString(),
        "x1" to brush.x1.toString(),
        "y1" to brush.y1.toString(),
        "r" to brush.radius.toString(),
        "tileMode" to when (brush.tileMode) {
            TileMode.Mirror -> "mirror"
            TileMode.Repeated -> "repeat"
            else -> "clamp"
        },
        "matrix" to brush.matrix.values().joinToString(" "),
        "colors" to brush.colors.joinToString(" ") { hex(it) },
        "offsets" to brush.offsets.joinToString(" ")
    )

    /** The gradient an element describes, or null when it is malformed (the solid fallback then stays). */
    fun decode(attribute: (String) -> String?): VectorGradientBrush? = runCatching {
        val type = when (attribute("type")) {
            "linear" -> GradientType.LINEAR
            "radial" -> GradientType.RADIAL
            else -> return null
        }
        val tileMode = when (attribute("tileMode")) {
            "mirror" -> TileMode.Mirror
            "repeat" -> TileMode.Repeated
            else -> TileMode.Clamp
        }
        val colors = attribute("colors").orEmpty().split(' ').filter(String::isNotEmpty).map(::parseHex)
        val offsets = attribute("offsets").orEmpty().split(' ').filter(String::isNotEmpty).map(String::toFloat)
        val matrix = GradientMatrix.of(attribute("matrix").orEmpty().split(' ').filter(String::isNotEmpty).map(String::toFloat))
            ?: return null
        fun float(name: String) = attribute(name)?.toFloat() ?: 0f
        VectorGradientBrush.of(
            type, colors, offsets, float("x0"), float("y0"), float("x1"), float("y1"), float("r"), tileMode, matrix
        ) as? VectorGradientBrush
    }.getOrNull()

    private fun hex(color: Color): String = "#" + color.toArgb().toUInt().toString(16).padStart(8, '0')

    private fun parseHex(value: String): Color {
        require(value.length == 9 && value[0] == '#')
        return Color(value.substring(1).toLong(16).toInt())
    }
}

