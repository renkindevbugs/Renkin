package dev.renkinProject.renkin.vector

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Minimal SVG reader for the vector editor's "Import SVG": extracts the viewBox and every
 * `<path>` element's geometry, keeping vectors vectors (no rasterising, no blur). Compose's
 * PathParser speaks the same `d` syntax, so the data feeds the editor directly.
 *
 * Deliberately small: paths only (no rect/circle/line primitives, no transforms, no CSS) —
 * icon sets like heroicons, Lucide or Tabler are pure paths. fill/stroke/stroke-width are
 * read per path with a one-level fallback to the root `<svg>` attributes, which is how those
 * sets inherit them.
 */
object SvgVectorImporter {

    /** One path: its `d` data, solid-fill vs stroke, and the stroke width when stroked. */
    data class ImportedPath(val pathData: String, val filled: Boolean, val strokeWidth: Float?)

    data class ImportedSvg(
        val viewportWidth: Float,
        val viewportHeight: Float,
        val paths: List<ImportedPath>
    )

    /** Null when the markup isn't usable SVG (no viewport, no paths, or not XML at all). */
    fun parse(markup: String): ImportedSvg? = try {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(markup))

        var viewportWidth = 0f
        var viewportHeight = 0f
        var sawSvg = false
        var rootFill: String? = null
        var rootStroke: String? = null
        var rootStrokeWidth: String? = null
        val paths = mutableListOf<ImportedPath>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "svg" -> if (!sawSvg) {
                        sawSvg = true
                        rootFill = parser.attr("fill")
                        rootStroke = parser.attr("stroke")
                        rootStrokeWidth = parser.attr("stroke-width")
                        val viewBox = parser.attr("viewBox")?.trim()
                            ?.split(Regex("[ ,]+"))
                            ?.mapNotNull { it.toFloatOrNull() }
                        if (viewBox != null && viewBox.size == 4) {
                            viewportWidth = viewBox[2]
                            viewportHeight = viewBox[3]
                        } else {
                            viewportWidth = parser.attr("width").toSvgSize()
                            viewportHeight = parser.attr("height").toSvgSize()
                        }
                    }
                    "path" -> {
                        val d = parser.attr("d")
                        if (!d.isNullOrBlank()) {
                            val fill = parser.attr("fill") ?: rootFill
                            val stroke = parser.attr("stroke") ?: rootStroke
                            val strokeWidth = (parser.attr("stroke-width") ?: rootStrokeWidth)?.toFloatOrNull()
                            // SVG paints fills black unless something says "none" — so an
                            // unspecified fill means a solid path, not a stroked one.
                            val filled = fill == null || !fill.equals("none", ignoreCase = true)
                            paths.add(ImportedPath(d, filled, strokeWidth?.takeIf { stroke != null && !stroke.equals("none", true) }))
                        }
                    }
                }
            }
            event = parser.next()
        }

        if (!sawSvg || viewportWidth <= 0f || viewportHeight <= 0f || paths.isEmpty()) null
        else ImportedSvg(viewportWidth, viewportHeight, paths)
    } catch (_: Exception) {
        null
    }

    private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

    private fun String?.toSvgSize(): Float =
        this?.trim()?.removeSuffix("px")?.toFloatOrNull() ?: 0f
}
