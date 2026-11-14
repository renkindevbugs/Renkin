package dev.renkinProject.renkin.icon.parser

import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import android.util.Xml
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.graphics.drawable.toBitmap
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.ImageVectorDrawable
import dev.renkinProject.renkin.drawable.InsetIconDrawable
import dev.renkinProject.renkin.drawable.haveMonochrome
import dev.renkinProject.renkin.drawable.isAdaptiveIconDrawable
import dev.renkinProject.renkin.drawable.newAdaptiveIconDrawable
import dev.renkinProject.renkin.extension.getAttributes
import dev.renkinProject.renkin.extension.getXmlOrNull
import dev.renkinProject.renkin.extension.isAtEndDocument
import dev.renkinProject.renkin.extension.parseUntil
import dev.renkinProject.renkin.extension.safeNext
import dev.renkinProject.renkin.extension.vectorResourceOrNull
import org.xmlpull.v1.XmlPullParser

class IconParser(private val resources: Resources, private val preserveFrameworkInsets: Boolean = false) {
    private fun parseDrawable(drawable: Drawable, drawableId: Int): Drawable {
        val parser = resources.getXmlOrNull(drawableId)
        return try {
            parseDrawable(drawable, drawableId, parser)
        } finally {
            parser?.close()
        }
    }

    private fun parseDrawable(drawable: Drawable): Drawable {
        return parseDrawable(drawable, -1, null)
    }

    private fun parseDrawable(drawable: Drawable, drawableId: Int, parser: XmlResourceParser?): Drawable {
        if (drawable.isAdaptiveIconDrawable() && parser != null) {
            return parseAdaptiveIcon(drawable as AdaptiveIconDrawable, parser) ?: drawable
        }

        return when (drawable) {
            is BitmapDrawable -> BitmapIconDrawable(drawable)
            is VectorDrawable -> parseVector(drawableId, parser) ?: drawable
            is InsetDrawable -> parseInset(drawable, parser) ?: drawable
            is LayerDrawable -> parseSingleLayer(drawable, parser) ?: drawable
            is ColorDrawable -> drawable
            else -> drawable
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun parseAdaptiveIcon(drawable: AdaptiveIconDrawable, parser: XmlResourceParser): AdaptiveIconDrawable? {
        var foreground: Drawable? = null
        var background: Drawable? = null
        var monochrome: Drawable? = null

        if (!parser.parseUntil("adaptive-icon")) return null

        while (!parser.isAtEndDocument()) {
            val current =
                parser.parseUntil(listOf("foreground", "background", "monochrome")) ?: break

            when (current) {
                "foreground" -> foreground = parseReferenceOrInnerDrawable(drawable.foreground, parser)
                "background" -> background = parseReferenceOrInnerDrawable(drawable.background, parser)
                "monochrome" -> monochrome = parseMonochrome(drawable, parser)
            }

            parser.safeNext()
        }

        if (foreground != null && background != null) {
            return newAdaptiveIconDrawable(foreground, background, monochrome)
        }

        return null
    }

    private fun parseMonochrome(drawable: AdaptiveIconDrawable, parser: XmlResourceParser): Drawable? {
        if (drawable.haveMonochrome()) {
            return parseReferenceOrInnerDrawable(drawable.monochrome!!, parser)
        }

        return null
    }

    private fun parseInset(insetDrawable: InsetDrawable, parser: XmlResourceParser?): Drawable? {
        if (insetDrawable.drawable == null) return null

        val drawable = if (parser != null) {
            if (!parser.parseUntil("inset")) return null
            parseReferenceOrInnerDrawable(insetDrawable.drawable!!, parser)
        } else {
            parseDrawable(insetDrawable.drawable!!)
        }

        // Clone the framework wrapper: its absolute and fractional insets can coexist on one side.
        val copy = if (preserveFrameworkInsets) {
            insetDrawable.constantState?.newDrawable(resources)?.mutate() as? InsetDrawable
        } else null
        return if (copy != null) copy.apply { setDrawable(drawable) }
        else InsetIconDrawable.from(insetDrawable, drawable)
    }

    /**
     * A one-item `<layer-list>` is only a wrapper: Lawnicons (2.x) puts its `<inset>` vector
     * foreground in one, and left unparsed the whole foreground stayed an opaque framework drawable.
     * Lists with several items or with item offsets are real compositions and are kept as they are.
     */
    private fun parseSingleLayer(layers: LayerDrawable, parser: XmlResourceParser?): Drawable? {
        if (layers.numberOfLayers != 1) return null
        val layer = layers.getDrawable(0) ?: return null
        val offset = layers.getLayerInsetLeft(0) or layers.getLayerInsetTop(0) or
            layers.getLayerInsetRight(0) or layers.getLayerInsetBottom(0)
        if (offset != 0) return null
        if (parser == null) return parseDrawable(layer)
        if (!parser.parseUntil("item")) return null
        return parseReferenceOrInnerDrawable(layer, parser)
    }

    private fun parseVector(drawableId: Int, parser: XmlResourceParser?): ImageVectorDrawable? {
        val vector = if (drawableId >= 0) {
            ImageVector.vectorResourceOrNull(resources, drawableId) ?: return null
        } else {
            if (parser == null) return null
            if (!parser.parseUntil("vector")) return null
            ImageVector.vectorResourceOrNull(resources, parser) ?: return null
        }

        return ImageVectorDrawable(vector, scaleStrokesWithBounds = preserveFrameworkInsets)
    }

    private fun parseColorDrawable(drawable: ColorDrawable): BitmapIconDrawable {
        return BitmapIconDrawable(drawable.toBitmap(108, 108))
    }

    private fun parseReferenceOrInnerDrawable(drawable: Drawable, parser: XmlResourceParser): Drawable {
        val attributes = parser.getAttributes()
        val drawableAttribute = attributes.find { it.name == "drawable" }

        val id = drawableAttribute?.value?.replace("@", "")?.toIntOrNull() ?: -1
        if (id > 0) return parseDrawable(drawable, id)
        return parseDrawable(drawable, id, parser)
    }

    companion object {
        fun readMonochromeLayer(resources: Resources, drawableId: Int): Drawable? = runCatching {
            val parser = resources.getXmlOrNull(drawableId) ?: return@runCatching null
            parser.use {
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "monochrome") {
                        val id = parser.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "drawable", 0)
                        if (id != 0) return@runCatching resources.getDrawable(id, null)
                        val depth = parser.depth
                        while (parser.next() != XmlPullParser.END_DOCUMENT) {
                            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
                            if (parser.eventType == XmlPullParser.START_TAG) {
                                return@runCatching Drawable.createFromXmlInner(resources, parser, Xml.asAttributeSet(parser), null)
                            }
                        }
                        return@runCatching null
                    }
                    parser.next()
                }
                null
            }
        }.getOrNull()

        fun parseDrawable(resources: Resources, drawable: Drawable, drawableId: Int, preserveFrameworkInsets: Boolean = false): Drawable {
            val parser = IconParser(resources, preserveFrameworkInsets)
            return parser.parseDrawable(drawable, drawableId)
        }
    }
}
