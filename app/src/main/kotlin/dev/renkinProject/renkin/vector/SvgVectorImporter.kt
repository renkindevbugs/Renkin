package dev.renkinProject.renkin.vector

import android.util.Xml
import androidx.compose.ui.graphics.vector.EmptyPath
import androidx.compose.ui.graphics.vector.PathParser
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Minimal SVG reader for the vector editor's "Import SVG": extracts the viewBox and every
 * `<path>` element's geometry, keeping vectors vectors (no rasterising, no blur). Compose's
 * PathParser speaks the same `d` syntax, so the data feeds the editor directly.
 *
 * Deliberately small: paths only (no rect/circle/line primitives, transforms or stylesheet
 * rules). Unsupported documents are rejected as a whole so the editor never presents a silently
 * partial icon. Inline fill/stroke/stroke-width attributes and simple style declarations inherit
 * through groups, which covers path-only icon sets such as Heroicons, Lucide and Tabler.
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
        val paths = mutableListOf<ImportedPath>()
        val stylesByDepth = mutableMapOf<Int, SvgStyle>()
        var unsupported = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "svg" -> if (!sawSvg) {
                        sawSvg = true
                        stylesByDepth[parser.depth] = SvgStyle().merge(parser)
                        if (!parser.attr("transform").isNullOrBlank()) unsupported = true
                        val viewBox = parser.attr("viewBox")?.trim()
                            ?.split(Regex("[ ,]+"))
                            ?.mapNotNull { it.toFloatOrNull() }
                        if (viewBox != null && viewBox.size == 4) {
                            // The editor has no viewBox-origin field; accepting a non-zero origin
                            // would silently shift/crop every path.
                            if (viewBox[0] != 0f || viewBox[1] != 0f) unsupported = true
                            viewportWidth = viewBox[2]
                            viewportHeight = viewBox[3]
                        } else {
                            viewportWidth = parser.attr("width").toSvgSize()
                            viewportHeight = parser.attr("height").toSvgSize()
                        }
                    }
                    "g" -> {
                        val inherited = stylesByDepth[parser.depth - 1] ?: SvgStyle()
                        stylesByDepth[parser.depth] = inherited.merge(parser)
                        if (!parser.attr("transform").isNullOrBlank()) unsupported = true
                    }
                    "path" -> {
                        if (!parser.attr("transform").isNullOrBlank()) unsupported = true
                        val d = parser.attr("d")
                        if (!d.isNullOrBlank()) {
                            val inherited = stylesByDepth[parser.depth - 1] ?: stylesByDepth.values.lastOrNull() ?: SvgStyle()
                            val style = inherited.merge(parser)
                            val fill = style.fill
                            val stroke = style.stroke
                            val strokeWidth = style.strokeWidth?.toFloatOrNull()
                            // SVG paints fills black unless something says "none" — so an
                            // unspecified fill means a solid path, not a stroked one.
                            val filled = fill == null || !fill.equals("none", ignoreCase = true)
                            val stroked = stroke != null && !stroke.equals("none", ignoreCase = true)
                            // The editor models a path as fill OR stroke. Reject dual-painted paths
                            // instead of silently dropping one paint during import.
                            if (filled && stroked) unsupported = true
                            val nodes = runCatching { PathParser().parsePathString(d).toNodes() }.getOrNull()
                            if (nodes == null || nodes == EmptyPath) unsupported = true
                            paths.add(ImportedPath(d, filled, strokeWidth?.takeIf { stroke != null && !stroke.equals("none", true) }))
                        }
                    }
                    "rect", "circle", "ellipse", "line", "polyline", "polygon", "use", "image", "text",
                    "clipPath", "mask", "pattern", "symbol", "defs", "style" ->
                        unsupported = true
                }
            }
            if (event == XmlPullParser.END_TAG && (parser.name == "g" || parser.name == "svg")) {
                stylesByDepth.remove(parser.depth)
            }
            event = parser.next()
        }

        if (unsupported || !sawSvg || viewportWidth <= 0f || viewportHeight <= 0f || paths.isEmpty()) null
        else ImportedSvg(viewportWidth, viewportHeight, paths)
    } catch (_: Exception) {
        null
    }

    private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

    private data class SvgStyle(
        val fill: String? = null,
        val stroke: String? = null,
        val strokeWidth: String? = null
    ) {
        fun merge(parser: XmlPullParser): SvgStyle {
            val declarations = parser.attr("style")
                ?.split(';')
                ?.mapNotNull { declaration ->
                    val parts = declaration.split(':', limit = 2)
                    if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                }
                ?.toMap()
                .orEmpty()
            return SvgStyle(
                fill = parser.attr("fill") ?: declarations["fill"] ?: fill,
                stroke = parser.attr("stroke") ?: declarations["stroke"] ?: stroke,
                strokeWidth = parser.attr("stroke-width") ?: declarations["stroke-width"] ?: strokeWidth
            )
        }
    }

    private fun String?.toSvgSize(): Float =
        this?.trim()?.removeSuffix("px")?.toFloatOrNull() ?: 0f
}
