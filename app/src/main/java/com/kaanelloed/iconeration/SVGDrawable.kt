package com.kaanelloed.iconeration

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import com.caverock.androidsvg.SVG

class SVGDrawable(private val svg: SVG): Drawable() {
    override fun draw(canvas: Canvas) {
        svg.renderToCanvas(canvas)
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

    override fun getIntrinsicHeight(): Int {
        return svg.documentViewBox.height().toInt()
    }

    override fun getIntrinsicWidth(): Int {
        return svg.documentViewBox.width().toInt()
    }
}