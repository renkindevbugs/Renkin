package dev.renkinProject.renkin.vector

import android.graphics.PathMeasure
import android.util.Xml
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.toPath
import dev.renkinProject.renkin.vector.brush.GradientMatrix
import dev.renkinProject.renkin.vector.brush.GradientType
import dev.renkinProject.renkin.vector.brush.VectorGradientBrush
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The `<linearGradient>`/`<radialGradient>` definitions of one SVG document, resolved into editor
 * brushes. Everything SVG would paint differently from a stored matrix gradient (focal radials,
 * `currentColor` stops, broken references) resolves to null, and the importer then rejects the
 * whole document rather than flattening it.
 */
internal class SvgGradients private constructor(private val definitions: Map<String, Definition>) {

    private class Definition(val radial: Boolean, val attributes: Map<String, String>, val stops: MutableList<Map<String, String>> = mutableListOf())

    /**
     * The brush for a `url(#id)` paint on a path with [nodes], with [alpha] (paint × element × group
     * opacity) folded into the stop colours. Null when the reference cannot be represented.
     */
    fun resolve(paint: String, nodes: List<PathNode>, alpha: Float, viewportWidth: Float, viewportHeight: Float): Brush? {
        val id = urlId(paint) ?: return null
        val chain = chain(id) ?: return null
        val gradient = chain.first()
        fun attribute(name: String): String? = chain.firstNotNullOfOrNull { it.attributes[name] }
        val stops = chain.firstOrNull { it.stops.isNotEmpty() }?.stops ?: return null

        val boundingBox = (attribute("gradientUnits") ?: "objectBoundingBox") == "objectBoundingBox"
        val diagonal = sqrt((viewportWidth * viewportWidth + viewportHeight * viewportHeight) / 2)
        fun length(name: String, default: String, base: Float): Float? {
            val raw = (attribute(name) ?: default).trim()
            return if (raw.endsWith('%')) {
                raw.dropLast(1).toFloatOrNull()?.div(100f)?.let { if (boundingBox) it else it * base }
            } else raw.removeSuffix("px").toFloatOrNull()
        }

        val transform = attribute("gradientTransform")?.let(::parseTransform) ?: GradientMatrix.IDENTITY
        val matrix = if (boundingBox) {
            val bounds = tightBounds(nodes) ?: return null
            if (bounds.width <= 0f || bounds.height <= 0f) return null
            GradientMatrix(bounds.width, 0f, 0f, bounds.height, bounds.left, bounds.top) * transform
        } else transform

        val tileMode = when (attribute("spreadMethod")) {
            null, "pad" -> TileMode.Clamp
            "reflect" -> TileMode.Mirror
            "repeat" -> TileMode.Repeated
            else -> return null
        }

        val offsets = mutableListOf<Float>()
        val colors = mutableListOf<Color>()
        for (stop in stops) {
            val offsetRaw = stop["offset"]?.trim() ?: "0"
            val offset = (if (offsetRaw.endsWith('%')) offsetRaw.dropLast(1).toFloatOrNull()?.div(100f) else offsetRaw.toFloatOrNull())
                ?: return null
            offsets += offset.coerceIn(0f, 1f).coerceAtLeast(offsets.lastOrNull() ?: 0f)
            val colorRaw = stop["stop-color"]?.trim() ?: "black"
            if (colorRaw.equals("currentColor", ignoreCase = true)) return null
            val color = ColorDecoder.decodeSvgCss(colorRaw).takeIf { it != Color.Unspecified } ?: return null
            val opacityRaw = stop["stop-opacity"]?.trim()
            val opacity = if (opacityRaw == null) 1f else unitFloat(opacityRaw) ?: return null
            colors += color.copy(alpha = (color.alpha * opacity * alpha).coerceIn(0f, 1f))
        }

        return if (gradient.radial) {
            val cx = length("cx", "50%", viewportWidth) ?: return null
            val cy = length("cy", "50%", viewportHeight) ?: return null
            val r = length("r", "50%", diagonal) ?: return null
            val fx = attribute("fx")?.let { length("fx", it, viewportWidth) ?: return null }
            val fy = attribute("fy")?.let { length("fy", it, viewportHeight) ?: return null }
            val fr = attribute("fr")?.let { length("fr", it, diagonal) ?: return null }
            if ((fx != null && fx != cx) || (fy != null && fy != cy) || (fr != null && fr != 0f)) return null
            VectorGradientBrush.of(GradientType.RADIAL, colors, offsets, cx, cy, 0f, 0f, r, tileMode, matrix)
        } else {
            val x1 = length("x1", "0%", viewportWidth) ?: return null
            val y1 = length("y1", "0%", viewportHeight) ?: return null
            val x2 = length("x2", "100%", viewportWidth) ?: return null
            val y2 = length("y2", "0%", viewportHeight) ?: return null
            VectorGradientBrush.of(GradientType.LINEAR, colors, offsets, x1, y1, x2, y2, 0f, tileMode, matrix)
        }
    }

    /** The referenced gradient followed by its `href` ancestors, nearest first; null on a broken or cyclic chain. */
    private fun chain(id: String): List<Definition>? {
        val seen = mutableSetOf<String>()
        val chain = mutableListOf<Definition>()
        var next: String? = id
        while (next != null) {
            if (!seen.add(next) || seen.size > MAX_HREF_DEPTH) return null
            val definition = definitions[next] ?: return null
            chain += definition
            next = (definition.attributes["xlink:href"] ?: definition.attributes["href"])?.let { href ->
                href.trim().removePrefix("#").takeIf { href.trim().startsWith("#") } ?: return null
            }
        }
        return chain
    }

    companion object {
        private const val MAX_HREF_DEPTH = 8
        private val TRANSFORM = Regex("""(matrix|translate|scale|rotate|skewX|skewY)\s*\(([^)]*)\)""")
        private val URL = Regex("""^\s*url\(\s*['"]?#([^'")\s]+)['"]?\s*\)""")

        /** Whether [paint] references a paint server (`url(...)`), possibly with a fallback colour. */
        fun isUrl(paint: String): Boolean = paint.trim().startsWith("url(", ignoreCase = true)

        private fun urlId(paint: String): String? = URL.find(paint)?.groupValues?.get(1)

        /** Collects every gradient definition of [markup]; stops come from `<stop>` children. */
        fun collect(markup: String): SvgGradients {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(markup))
            val definitions = mutableMapOf<String, Definition>()
            var open: Pair<String?, Definition>? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "linearGradient", "radialGradient" -> {
                            val attributes = parser.attributes()
                            open = attributes["id"] to Definition(parser.name == "radialGradient", attributes)
                        }
                        "stop" -> open?.second?.stops?.add(parser.stopAttributes())
                    }
                }
                if (event == XmlPullParser.END_TAG && (parser.name == "linearGradient" || parser.name == "radialGradient")) {
                    open?.let { (id, definition) -> if (id != null) definitions[id] = definition }
                    open = null
                }
                event = parser.next()
            }
            return SvgGradients(definitions)
        }

        private fun XmlPullParser.attributes(): Map<String, String> =
            (0 until attributeCount).associate { getAttributeName(it) to getAttributeValue(it) }

        /** Stop attributes with `style` declarations folded in (attributes win, as for paths). */
        private fun XmlPullParser.stopAttributes(): Map<String, String> {
            val attributes = attributes()
            val declarations = attributes["style"]?.split(';')?.mapNotNull { declaration ->
                val parts = declaration.split(':', limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }?.toMap().orEmpty()
            return declarations + attributes
        }

        private fun unitFloat(raw: String): Float? {
            val value = if (raw.endsWith('%')) raw.dropLast(1).toFloatOrNull()?.div(100f) else raw.toFloatOrNull()
            return value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        }

        /** An SVG transform list as one matrix (left to right, like nested transforms); null when malformed. */
        fun parseTransform(raw: String): GradientMatrix? {
            var result = GradientMatrix.IDENTITY
            var consumed = 0
            for (match in TRANSFORM.findAll(raw)) {
                if (raw.substring(consumed, match.range.first).any { !it.isWhitespace() && it != ',' }) return null
                consumed = match.range.last + 1
                val values = match.groupValues[2].split(Regex("[\\s,]+")).filter(String::isNotEmpty)
                    .map { it.toFloatOrNull() ?: return null }
                val step = when (match.groupValues[1]) {
                    "matrix" -> GradientMatrix.of(values) ?: return null
                    "translate" -> when (values.size) {
                        1 -> GradientMatrix.translate(values[0], 0f)
                        2 -> GradientMatrix.translate(values[0], values[1])
                        else -> return null
                    }
                    "scale" -> when (values.size) {
                        1 -> GradientMatrix.scale(values[0], values[0])
                        2 -> GradientMatrix.scale(values[0], values[1])
                        else -> return null
                    }
                    "rotate" -> when (values.size) {
                        1 -> GradientMatrix.rotate(values[0])
                        3 -> GradientMatrix.rotate(values[0], values[1], values[2])
                        else -> return null
                    }
                    "skewX" -> values.singleOrNull()?.let { GradientMatrix(1f, 0f, tan(it * PI / 180).toFloat(), 1f, 0f, 0f) } ?: return null
                    "skewY" -> values.singleOrNull()?.let { GradientMatrix(1f, tan(it * PI / 180).toFloat(), 0f, 1f, 0f, 0f) } ?: return null
                    else -> return null
                }
                result *= step
            }
            if (raw.substring(consumed).any { !it.isWhitespace() && it != ',' }) return null
            return result
        }

        /**
         * The geometry box SVG uses for objectBoundingBox, sampled along the outline: Android's own
         * bounds include curve control points, which would shift gradients on curved logos.
         */
        private fun tightBounds(nodes: List<PathNode>): Rect? {
            val measure = PathMeasure(nodes.toPath().asAndroidPath(), false)
            val position = FloatArray(2)
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            do {
                val length = measure.length
                val samples = 256
                for (i in 0..samples) {
                    if (!measure.getPosTan(length * i / samples, position, null)) continue
                    left = min(left, position[0]); top = min(top, position[1])
                    right = max(right, position[0]); bottom = max(bottom, position[1])
                }
            } while (measure.nextContour())
            return if (left.isFinite() && top.isFinite()) Rect(left, top, right, bottom) else null
        }
    }
}
