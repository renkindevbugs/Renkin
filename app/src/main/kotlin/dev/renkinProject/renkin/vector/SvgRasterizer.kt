package dev.renkinProject.renkin.vector

import android.graphics.Bitmap
import android.graphics.RectF
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import dev.renkinProject.renkin.extension.newArgbBitmap
import dev.renkinProject.renkin.extension.toRgbHexString
import kotlin.math.max

/**
 * Renders whole SVG documents to bitmaps with AndroidSVG. Where [SvgVectorImporter] accepts
 * only what the vector editor can model, this draws everything AndroidSVG understands
 * (gradients, clip paths, `<use>`/`<defs>`), so full-colour icons can still be shown
 * faithfully — as uneditable pixels — where a strict vector import isn't possible.
 */
object SvgRasterizer {

    /**
     * Parses SVG markup, resolving `currentColor` to [currentColorArgb] up front — icon sets
     * use it throughout, and unresolved it draws nothing (a blank render).
     */
    fun decode(markup: String, currentColorArgb: Int = android.graphics.Color.BLACK): SVG? = try {
        SVG.getFromString(
            markup.replace("currentColor", currentColorArgb.toRgbHexString())
        )
    } catch (_: SVGParseException) {
        null
    }

    /**
     * Raster size for [svg]: the longest side always lands on [longestSide] — up or down,
     * because an SVG's document size is a hint, not pixels; a 24x24 icon document rendered
     * 1:1 and enlarged later is exactly the blur vectors exist to avoid. The aspect comes
     * from the viewBox when there is one (always plain user units) and the document
     * width/height otherwise; null when neither yields a drawable area.
     */
    fun renderSize(svg: SVG, longestSide: Int): Pair<Int, Int>? {
        val viewBox = svg.documentViewBox
        val docWidth = viewBox?.width() ?: svg.documentWidth
        val docHeight = viewBox?.height() ?: svg.documentHeight
        if (docWidth <= 0 || docHeight <= 0) return null
        val scale = longestSide / max(docWidth, docHeight)
        val width = (docWidth * scale).toInt().coerceAtLeast(1)
        val height = (docHeight * scale).toInt().coerceAtLeast(1)
        return width to height
    }

    /** [markup] drawn into a fresh bitmap, or null when it isn't renderable SVG. */
    fun rasterize(
        markup: String,
        longestSide: Int,
        currentColorArgb: Int = android.graphics.Color.BLACK
    ): Bitmap? {
        val svg = decode(markup, currentColorArgb) ?: return null
        val (width, height) = renderSize(svg, longestSide) ?: return null
        // Make the document fill the render viewport. Declared sizes like "1em" resolve
        // against the font size at render time, drawing the art tiny in a corner of the
        // bitmap; a percentage width scales the viewBox to whatever viewport we pick.
        runCatching {
            if (svg.documentViewBox == null) {
                svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
            }
            svg.setDocumentWidth("100%")
            svg.setDocumentHeight("100%")
        }.getOrElse { return null }
        return runCatching {
            newArgbBitmap(width, height) {
                svg.renderToCanvas(it, RectF(0f, 0f, width.toFloat(), height.toFloat()))
            }
        }.getOrNull()
    }
}
