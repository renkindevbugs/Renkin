package dev.renkinProject.renkin.vector

import android.util.Xml
import androidx.compose.ui.graphics.vector.EmptyPath
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Minimal SVG reader for the vector editor's "Import SVG": extracts the viewBox and the
 * geometry of every `<path>` — plus the basic shapes (`rect`/`circle`/`ellipse`/`line`/
 * `polyline`/`polygon`), which are converted to equivalent path data — keeping vectors
 * vectors (no rasterising, no blur). Compose's PathParser speaks the same `d` syntax, so
 * the data feeds the editor directly.
 *
 * Deliberately small beyond that: no transforms, `<use>`/`<defs>` or stylesheet rules.
 * Unsupported documents are rejected as a whole so the editor never presents a silently
 * partial icon. Inline fill/stroke/stroke-width attributes and simple style declarations
 * inherit through groups, which covers icon sets such as Heroicons, Lucide and Tabler.
 */
object SvgVectorImporter {

    /**
     * One path: its `d` data, solid-fill vs stroke, the stroke width when stroked, and the
     * active paint's raw colour value ("#e91e63", "rgb(…)", …) — null when the document
     * leaves it to the renderer ("currentColor", or nothing declared), which the editor
     * paints white for the Modifier tab to recolour.
     */
    data class ImportedPath(
        val pathData: String,
        val filled: Boolean,
        val strokeWidth: Float?,
        val color: String? = null,
        val alpha: Float = 1f,
        val fillType: PathFillType = PathFillType.NonZero,
        val strokeLineCap: StrokeCap = StrokeCap.Butt,
        val strokeLineJoin: StrokeJoin = StrokeJoin.Miter
    )

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
                        parser.property("opacity")?.let { opacity ->
                            if (opacity.toUnitFloatOrNull() != 1f) unsupported = true
                        }
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
                        parser.property("opacity")?.let { opacity ->
                            if (opacity.toUnitFloatOrNull() != 1f) unsupported = true
                        }
                    }
                    "path", "rect", "circle", "ellipse", "line", "polyline", "polygon" -> {
                        if (!parser.attr("transform").isNullOrBlank()) unsupported = true
                        // Shapes become equivalent path data; a malformed shape (missing or
                        // non-positive dimensions) rejects the document like any other
                        // unsupported construct.
                        val d = if (parser.name == "path") parser.attr("d") else parser.shapePathData()
                        if (parser.name != "path" && d == null) unsupported = true
                        if (!d.isNullOrBlank()) {
                            val inherited = stylesByDepth[parser.depth - 1] ?: stylesByDepth.values.lastOrNull() ?: SvgStyle()
                            val style = inherited.merge(parser)
                            val fill = style.fill
                            val stroke = style.stroke
                            val parsedStrokeWidth = style.strokeWidth?.trim()?.removeSuffix("px")?.toFloatOrNull()
                            // SVG paints fills black unless something says "none" — so an
                            // unspecified fill means a solid path, not a stroked one. Lines
                            // and polylines are never filled: for icons the spec's implicit
                            // black polyline fill is always an accident.
                            val neverFilled = parser.name == "line" || parser.name == "polyline"
                            val filled = !neverFilled &&
                                (fill == null || !fill.equals("none", ignoreCase = true))
                            val stroked = stroke != null && !stroke.equals("none", ignoreCase = true)
                            if (stroked && style.strokeWidth != null && parsedStrokeWidth == null) {
                                unsupported = true
                            }
                            val strokeWidth = parsedStrokeWidth ?: 1f
                            // The editor models a path as fill OR stroke. Reject dual-painted paths
                            // instead of silently dropping one paint during import.
                            if (filled && stroked) unsupported = true
                            val nodes = runCatching { PathParser().parsePathString(d).toNodes() }.getOrNull()
                            if (nodes == null || nodes == EmptyPath) unsupported = true
                            // The active paint's own colour (multicolour icon sets); gradients
                            // and other paint servers can't become a solid path colour, so they
                            // reject the document rather than silently flattening it.
                            val paint = if (filled) fill else stroke
                            if (paint != null && paint.trim().startsWith("url(", ignoreCase = true)) {
                                unsupported = true
                            }
                            val color = paint?.trim()?.takeIf {
                                !it.equals("none", true) && !it.equals("currentColor", true)
                            }
                            val paintAlphaRaw = if (filled) style.fillOpacity else style.strokeOpacity
                            val paintAlpha = paintAlphaRaw?.toUnitFloatOrNull() ?: 1f
                            val elementAlpha = parser.property("opacity")?.toUnitFloatOrNull() ?: 1f
                            if ((paintAlphaRaw != null && paintAlphaRaw.toUnitFloatOrNull() == null) ||
                                (parser.property("opacity") != null &&
                                    parser.property("opacity")?.toUnitFloatOrNull() == null)
                            ) unsupported = true
                            val fillType = when (style.fillRule?.lowercase()) {
                                null, "nonzero" -> PathFillType.NonZero
                                "evenodd" -> PathFillType.EvenOdd
                                else -> { unsupported = true; PathFillType.NonZero }
                            }
                            val lineCap = when (style.strokeLineCap?.lowercase()) {
                                null, "butt" -> StrokeCap.Butt
                                "round" -> StrokeCap.Round
                                "square" -> StrokeCap.Square
                                else -> { unsupported = true; StrokeCap.Butt }
                            }
                            val lineJoin = when (style.strokeLineJoin?.lowercase()) {
                                null, "miter" -> StrokeJoin.Miter
                                "round" -> StrokeJoin.Round
                                "bevel" -> StrokeJoin.Bevel
                                else -> { unsupported = true; StrokeJoin.Miter }
                            }
                            paths.add(
                                ImportedPath(
                                    d,
                                    filled,
                                    strokeWidth.takeIf { stroked },
                                    color,
                                    (paintAlpha * elementAlpha).coerceIn(0f, 1f),
                                    fillType,
                                    lineCap,
                                    lineJoin
                                )
                            )
                        }
                    }
                    "use", "image", "text",
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

    private fun XmlPullParser.property(name: String): String? {
        attr(name)?.let { return it }
        return attr("style")?.split(';')?.firstNotNullOfOrNull { declaration ->
            val parts = declaration.split(':', limit = 2)
            parts.takeIf { it.size == 2 && it[0].trim() == name }?.get(1)?.trim()
        }
    }

    private fun String.toUnitFloatOrNull(): Float? {
        val parsed = if (endsWith('%')) dropLast(1).toFloatOrNull()?.div(100f) else toFloatOrNull()
        return parsed?.takeIf { it in 0f..1f }
    }

    private fun XmlPullParser.attrF(name: String): Float? =
        attr(name)?.trim()?.removeSuffix("px")?.toFloatOrNull()

    /**
     * SVG coordinates default only when the attribute is absent. An explicit unsupported
     * value (percentages, other units or malformed text) returns null so the whole document
     * is rejected instead of quietly moving the shape to zero.
     */
    private fun XmlPullParser.attrFOrDefault(name: String, default: Float): Float? {
        if (attr(name) == null) return default
        return attrF(name)
    }

    /**
     * The current basic shape as equivalent path data, or null when its attributes don't
     * describe a drawable shape. Pure coordinate maths — the SVG spec defines each shape
     * exactly in these terms, so nothing is approximated.
     */
    private fun XmlPullParser.shapePathData(): String? {
        return when (name) {
            "rect" -> {
                val x = attrFOrDefault("x", 0f) ?: return null
                val y = attrFOrDefault("y", 0f) ?: return null
                val w = attrFOrDefault("width", 0f) ?: return null
                val h = attrFOrDefault("height", 0f) ?: return null
                // rx/ry default to each other and clamp to half the side, per the spec.
                val declaredRx = attrFOrDefault("rx", Float.NaN) ?: return null
                val declaredRy = attrFOrDefault("ry", Float.NaN) ?: return null
                var rx = declaredRx.takeUnless { it.isNaN() }
                    ?: declaredRy.takeUnless { it.isNaN() }
                    ?: 0f
                var ry = declaredRy.takeUnless { it.isNaN() }
                    ?: declaredRx.takeUnless { it.isNaN() }
                    ?: 0f
                rx = rx.coerceIn(0f, w / 2f)
                ry = ry.coerceIn(0f, h / 2f)
                when {
                    w <= 0f || h <= 0f -> null
                    rx <= 0f || ry <= 0f -> "M$x $y h$w v$h h${-w} Z"
                    else -> "M${x + rx} $y H${x + w - rx} A$rx $ry 0 0 1 ${x + w} ${y + ry} " +
                        "V${y + h - ry} A$rx $ry 0 0 1 ${x + w - rx} ${y + h} H${x + rx} " +
                        "A$rx $ry 0 0 1 $x ${y + h - ry} V${y + ry} A$rx $ry 0 0 1 ${x + rx} $y Z"
                }
            }

            "circle" -> {
                val cx = attrFOrDefault("cx", 0f) ?: return null
                val cy = attrFOrDefault("cy", 0f) ?: return null
                val r = attrFOrDefault("r", 0f) ?: return null
                if (r <= 0f) null else ellipsePathData(cx, cy, r, r)
            }

            "ellipse" -> {
                val cx = attrFOrDefault("cx", 0f) ?: return null
                val cy = attrFOrDefault("cy", 0f) ?: return null
                val rx = attrFOrDefault("rx", 0f) ?: return null
                val ry = attrFOrDefault("ry", 0f) ?: return null
                if (rx <= 0f || ry <= 0f) null
                else ellipsePathData(cx, cy, rx, ry)
            }

            "line" -> {
                val x1 = attrFOrDefault("x1", 0f) ?: return null
                val y1 = attrFOrDefault("y1", 0f) ?: return null
                val x2 = attrFOrDefault("x2", 0f) ?: return null
                val y2 = attrFOrDefault("y2", 0f) ?: return null
                if (x1 == x2 && y1 == y2) null else "M$x1 $y1 L$x2 $y2"
            }

            "polyline", "polygon" -> {
                val pointValues = attr("points")?.trim()
                    ?.split(Regex("[ ,\\n\\t]+"))
                    ?.map { it.toFloatOrNull() }
                    .orEmpty()
                if (pointValues.any { it == null }) return null
                val points = pointValues.filterNotNull()
                if (points.size < 4 || points.size % 2 != 0) null
                else buildString {
                    append("M${points[0]} ${points[1]}")
                    for (i in 2 until points.size step 2) append(" L${points[i]} ${points[i + 1]}")
                    if (this@shapePathData.name == "polygon") append(" Z")
                }
            }

            else -> null
        }
    }

    /** An ellipse as two arcs, starting at its left-most point. */
    private fun ellipsePathData(cx: Float, cy: Float, rx: Float, ry: Float): String =
        "M${cx - rx} $cy a$rx $ry 0 1 0 ${2 * rx} 0 a$rx $ry 0 1 0 ${-2 * rx} 0 Z"

    private data class SvgStyle(
        val fill: String? = null,
        val stroke: String? = null,
        val strokeWidth: String? = null,
        val fillRule: String? = null,
        val fillOpacity: String? = null,
        val strokeOpacity: String? = null,
        val strokeLineCap: String? = null,
        val strokeLineJoin: String? = null
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
                strokeWidth = parser.attr("stroke-width") ?: declarations["stroke-width"] ?: strokeWidth,
                fillRule = parser.attr("fill-rule") ?: declarations["fill-rule"] ?: fillRule,
                fillOpacity = parser.attr("fill-opacity") ?: declarations["fill-opacity"] ?: fillOpacity,
                strokeOpacity = parser.attr("stroke-opacity") ?: declarations["stroke-opacity"] ?: strokeOpacity,
                strokeLineCap = parser.attr("stroke-linecap") ?: declarations["stroke-linecap"] ?: strokeLineCap,
                strokeLineJoin = parser.attr("stroke-linejoin") ?: declarations["stroke-linejoin"] ?: strokeLineJoin
            )
        }
    }

    private fun String?.toSvgSize(): Float =
        this?.trim()?.removeSuffix("px")?.toFloatOrNull() ?: 0f
}
