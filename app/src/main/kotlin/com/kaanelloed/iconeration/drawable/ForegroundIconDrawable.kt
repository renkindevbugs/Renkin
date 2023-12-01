package com.kaanelloed.iconeration.drawable

import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable

class ForegroundIconDrawable(drawable: Drawable): AdaptiveIconDrawable(null, drawable) {
    override fun draw(canvas: Canvas) {
        foreground.draw(canvas)
    }
}