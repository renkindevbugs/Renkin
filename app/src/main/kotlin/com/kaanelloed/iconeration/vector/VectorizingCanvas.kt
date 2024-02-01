package com.kaanelloed.iconeration.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

class VectorizingCanvas: Canvas() {
    val paths = mutableMapOf<Path, Paint>()

    init {
        super.setBitmap(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888))
    }

    override fun drawPath(path: Path, paint: Paint) {
        paths[path] = paint

        super.drawPath(path, paint)
    }
}