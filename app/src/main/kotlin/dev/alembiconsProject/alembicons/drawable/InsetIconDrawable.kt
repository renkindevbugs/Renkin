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
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeRect
import androidx.core.graphics.drawable.toBitmap
import dev.alembiconsProject.alembicons.packages.PackageVersion
import dev.alembiconsProject.alembicons.vector.VectorEditor.Companion.inset

class InsetIconDrawable(val drawable: Drawable, val dimensions: Rect, val fractions: RectF): Drawable(), IconPackDrawable {
    private val insetDrawable: InsetDrawable
    private val isFractionsNotEmpty = isFractions()

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
        TODO("Not yet implemented")
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