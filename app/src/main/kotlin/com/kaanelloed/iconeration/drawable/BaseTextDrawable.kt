package com.kaanelloed.iconeration.drawable

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Path
import android.graphics.drawable.Drawable

abstract class BaseTextDrawable: Drawable() {
    abstract fun getPaths(): List<Path>

    override fun draw(canvas: Canvas) {
        TODO("Not yet implemented")
    }

    override fun setAlpha(alpha: Int) {
        TODO("Not yet implemented")
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        TODO("Not yet implemented")
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int {
        TODO("Not yet implemented")
    }
}