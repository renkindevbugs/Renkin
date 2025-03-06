package dev.alembiconsProject.alembicons.drawable

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import dev.alembiconsProject.alembicons.extension.toBase64

class BitmapIconDrawable(val drawable: BitmapDrawable, private val exportAsAdaptiveIcon: Boolean = false) :
    IconPackDrawable() {
    constructor(bitmap: Bitmap, exportAsAdaptiveIcon: Boolean = false) : this(
        BitmapDrawable(
            null,
            bitmap
        ), exportAsAdaptiveIcon
    )

    override fun draw(canvas: Canvas) {
        drawable.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        drawable.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        drawable.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("drawable.opacity"))
    override fun getOpacity(): Int {
        return drawable.opacity
    }

    @Composable
    override fun getPainter(): Painter {
        return BitmapPainter(drawable.bitmap.asImageBitmap())
    }

    override fun toBitmap(): Bitmap {
        return drawable.bitmap
    }

    override fun toDbString(): String {
        return drawable.bitmap.toBase64(Bitmap.CompressFormat.PNG, 100)
    }

    override fun isAdaptiveIcon(): Boolean {
        return exportAsAdaptiveIcon
    }
}