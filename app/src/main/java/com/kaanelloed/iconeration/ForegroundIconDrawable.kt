package com.kaanelloed.iconeration

import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable

class ForegroundIconDrawable(drawable: Drawable): AdaptiveIconDrawable(null, drawable) {
    override fun draw(canvas: Canvas) {
        foreground.draw(canvas)
    }
}