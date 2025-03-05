package dev.alembiconsProject.alembicons.drawable

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.util.Base64
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeRect
import androidx.core.graphics.drawable.toBitmap
import dev.alembiconsProject.alembicons.packages.PackageVersion
import dev.alembiconsProject.alembicons.vector.VectorEditor.Companion.inset
import dev.alembiconsProject.alembicons.vector.VectorExporter.Companion.toXmlFile
import dev.alembiconsProject.alembicons.xml.file.InsetXml

class InsetIconDrawable(val drawable: Drawable, val dimensions: Rect, val fractions: RectF): IconPackDrawable() {
    private val insetDrawable: InsetDrawable
    val isFractionsNotEmpty = isFractions()

    init {
        insetDrawable = if (isFractionsNotEmpty) {
            InsetDrawable(drawable, fractions.left, fractions.top, fractions.right, fractions.bottom)
        } else {
            InsetDrawable(drawable, dimensions.left, dimensions.top, dimensions.right, dimensions.bottom)
        }
    }

    override fun draw(canvas: Canvas) {
        insetDrawable.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        insetDrawable.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        insetDrawable.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("insetDrawable.opacity"))
    override fun getOpacity(): Int {
        return insetDrawable.opacity
    }

    @Composable
    override fun getPainter(): Painter {
        when (drawable) {
            is ImageVectorDrawable -> {
                val newVector = ImageVectorDrawable(drawable.toImageVector())
                newVector.inset(dimensions.toComposeRect())
                return newVector.getPainter()
            }
            is BitmapDrawable -> {
                return BitmapIconDrawable(insetDrawable.toBitmap()).getPainter()
            }
            else -> {
                return BitmapIconDrawable(ColorDrawable().toBitmap(108, 108)).getPainter()
            }
        }
    }

    override fun toBitmap(): Bitmap {
        return insetDrawable.toBitmap()
    }

    override fun toDbString(): String {
        val file = InsetXml()
        if (isFractionsNotEmpty) {
            file.inset((fractions.bottom * 100).toString() + "%"
                , (fractions.left * 100).toString() + "%"
                , (fractions.right * 100).toString() + "%"
                , (fractions.top * 100).toString() + "%")
        } else {
            file.inset(dimensions.bottom.toString() + "dp"
                , dimensions.left.toString() + "dp"
                , dimensions.right.toString() + "dp"
                , dimensions.top.toString() + "dp")
        }

        if (drawable is ImageVectorDrawable) {
            file.startVector()
            drawable.toImageVector().toXmlFile(file)
            file.endVector()
        }
        if (drawable is BitmapIconDrawable) {
            file.insetDrawable(drawable.toDbString())
        }
        return Base64.encodeToString(file.readAndClose(), Base64.NO_WRAP)
    }

    @ChecksSdkIntAtLeast(Build.VERSION_CODES.O)
    private fun isFractions(): Boolean {
        val emptyFraction = fractions.left >= 0 || fractions.right >= 0 || fractions.top >= 0 || fractions.bottom >= 0
        return PackageVersion.is26OrMore() && emptyFraction
    }

    companion object {
        fun from(insetDrawable: InsetDrawable): InsetIconDrawable {
            val dimensions = Rect()
            val fractions = RectF()
            insetDrawable.getInsetValues(dimensions, fractions)

            return InsetIconDrawable(insetDrawable.drawable!!, dimensions, fractions)
        }
    }
}