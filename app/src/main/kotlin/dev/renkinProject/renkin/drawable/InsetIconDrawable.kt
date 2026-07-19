package dev.renkinProject.renkin.drawable

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.util.Base64
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeRect
import dev.renkinProject.renkin.packages.PackageVersion
import dev.renkinProject.renkin.vector.VectorEditor.Companion.inset
import dev.renkinProject.renkin.vector.VectorExporter.Companion.toXmlFile
import dev.renkinProject.renkin.xml.file.InsetXml

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
        return when (drawable) {
            is ImageVectorDrawable -> {
                val newVector = ImageVectorDrawable(drawable.toImageVector())
                if (isFractionsNotEmpty) {
                    newVector.inset(fractions)
                } else {
                    newVector.inset(dimensions.toComposeRect())
                }
                newVector.getPainter()
            }
            else -> {
                BitmapIconDrawable(toBitmap()).getPainter()
            }
        }
    }

    override fun toBitmap(): Bitmap {
        // Vector-backed inset drawables commonly report no intrinsic dimensions (-1 x -1),
        // notably Lawnicons. Modifier operations rasterize through this method; asking for the
        // implicit size therefore returned null and turned a visible vector into a blank icon.
        insetDrawable.toSafeBitmapOrNull(MODIFIER_RASTER_SIZE, MODIFIER_RASTER_SIZE)?.let { return it }

        // A malformed third-party inset is still better shown without its padding than replaced
        // by a transparent bitmap. IconPackDrawable implementations own their safe raster path.
        if (drawable is IconPackDrawable) return drawable.toBitmap()
        return drawable.toSafeBitmapOrNull(MODIFIER_RASTER_SIZE, MODIFIER_RASTER_SIZE)
            ?: Bitmap.createBitmap(MODIFIER_RASTER_SIZE, MODIFIER_RASTER_SIZE, Bitmap.Config.ARGB_8888)
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
            file.base64Drawable(drawable.toDbString())
        }
        return Base64.encodeToString(file.readAndClose(), Base64.NO_WRAP)
    }

    @ChecksSdkIntAtLeast(Build.VERSION_CODES.O)
    private fun isFractions(): Boolean {
        val emptyFraction = fractions.left >= 0 || fractions.right >= 0 || fractions.top >= 0 || fractions.bottom >= 0
        return PackageVersion.is26OrMore() && emptyFraction
    }

    fun newDrawable(drawable: Drawable): InsetIconDrawable {
        return InsetIconDrawable(drawable, dimensions, fractions)
    }

    companion object {
        private const val MODIFIER_RASTER_SIZE = 256

        fun from(insetDrawable: InsetDrawable): InsetIconDrawable {
            return from(insetDrawable, insetDrawable.drawable!!)
        }

        fun from(insetDrawable: InsetDrawable, drawable: Drawable): InsetIconDrawable {
            val dimensions = Rect()
            val fractions = RectF()
            insetDrawable.getInsetValues(dimensions, fractions)

            return InsetIconDrawable(drawable, dimensions, fractions)
        }
    }
}
