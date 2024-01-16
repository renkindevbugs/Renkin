package com.kaanelloed.iconeration.drawable

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable

//https://android.googlesource.com/platform/packages/apps/Camera/+/master/src/com/android/camera/drawable/TextDrawable.java
class TextDrawable(private val text: CharSequence, typeFace: Typeface, textSize: Float, color: Int, strokeWidth: Float = 0F, adjustToSize: Int = 0): Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val _intrinsicWidth: Int
    private val _intrinsicHeight: Int

    init {
        paint.color = color
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = textSize
        paint.typeface = typeFace
        if (strokeWidth > 0F) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
        }
        if (adjustToSize > 0) {
            adjustTextSize(adjustToSize)
        }

        _intrinsicWidth = calculateIntrinsicWidth()
        _intrinsicHeight = Paint.FontMetricsInt().ascent
    }

    private fun adjustTextSize(maxWidth: Int) {
        while (calculateIntrinsicWidth() > maxWidth) {
            paint.textSize = paint.textSize - 1
        }
    }

    private fun calculateIntrinsicWidth(): Int {
        return (paint.measureText(text, 0, text.length) + 0.5).toInt()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int {
        return paint.alpha
    }

    override fun getIntrinsicWidth(): Int {
        return _intrinsicWidth
    }

    override fun getIntrinsicHeight(): Int {
        return _intrinsicHeight
    }

    override fun draw(canvas: Canvas) {
        //canvas.drawText(text, 0, text.length, bounds.exactCenterX(), bounds.exactCenterY(), paint)

        /*val y: Float = if (text.toString().uppercase() == text.toString()) {
            bounds.centerY().toFloat() - paint.ascent() / 2
        } else {
            bounds.centerY().toFloat() - ((paint.descent() + paint.ascent()) / 2)
        }*/

        canvas.drawText(text, 0, text.length,
            bounds.centerX().toFloat(), bounds.centerY().toFloat() - paint.ascent() / 2, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }
}