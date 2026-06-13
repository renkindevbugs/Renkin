package dev.alembiconsProject.alembicons.icon.parser

import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.graphics.drawable.toBitmap
import dev.alembiconsProject.alembicons.drawable.BitmapIconDrawable
import dev.alembiconsProject.alembicons.drawable.ImageVectorDrawable
import dev.alembiconsProject.alembicons.drawable.InsetIconDrawable
import dev.alembiconsProject.alembicons.drawable.haveMonochrome
import dev.alembiconsProject.alembicons.drawable.isAdaptiveIconDrawable
import dev.alembiconsProject.alembicons.drawable.newAdaptiveIconDrawable
import dev.alembiconsProject.alembicons.extension.getAttributes
import dev.alembiconsProject.alembicons.extension.getXmlOrNull
import dev.alembiconsProject.alembicons.extension.isAtEndDocument
import dev.alembiconsProject.alembicons.extension.parseUntil
import dev.alembiconsProject.alembicons.extension.safeNext
import dev.alembiconsProject.alembicons.extension.vectorResourceOrNull

class IconParser(private val resources: Resources) {
    private fun parseDrawable(drawable: Drawable, drawableId: Int): Drawable {
        val parser = resources.getXmlOrNull(drawableId)
        return parseDrawable(drawable, drawableId, parser)
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

        return InsetIconDrawable.from(insetDrawable, drawable)
    }

    private fun parseVector(drawableId: Int, parser: XmlResourceParser?): ImageVectorDrawable? {
        val vector = if (drawableId >= 0) {
            ImageVector.vectorResourceOrNull(resources, drawableId) ?: return null
        } else {
            if (parser == null) return null
            if (!parser.parseUntil("vector")) return null
            ImageVector.vectorResourceOrNull(resources, parser) ?: return null
        }

        return ImageVectorDrawable(vector)
    }

    private fun parseColorDrawable(drawable: ColorDrawable): BitmapIconDrawable {
        return BitmapIconDrawable(drawable.toBitmap(108, 108))
    }

    private fun parseReferenceOrInnerDrawable(drawable: Drawable, parser: XmlResourceParser): Drawable {
        val attributes = parser.getAttributes()
        val drawableAttribute = attributes.find { it.name == "drawable" }

        val id = drawableAttribute?.value?.replace("@", "")?.toIntOrNull() ?: -1
        return parseDrawable(drawable, id, parser)
    }

    companion object {
        fun parseDrawable(resources: Resources, drawable: Drawable, drawableId: Int): Drawable {
            val parser = IconParser(resources)
            return parser.parseDrawable(drawable, drawableId)
        }
    }
}