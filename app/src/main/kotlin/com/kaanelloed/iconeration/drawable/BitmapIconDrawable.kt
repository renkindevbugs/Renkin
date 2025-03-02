package com.kaanelloed.iconeration.drawable

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import com.kaanelloed.iconeration.extension.toBase64

class BitmapIconDrawable(val drawable: BitmapDrawable): Drawable(), IconPackDrawable {
    constructor(bitmap: Bitmap): this(BitmapDrawable(null, bitmap))

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

}