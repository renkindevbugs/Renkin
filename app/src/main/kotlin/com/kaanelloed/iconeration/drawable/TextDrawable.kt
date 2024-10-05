package com.kaanelloed.iconeration.drawable

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

//https://android.googlesource.com/platform/packages/apps/Camera/+/master/src/com/android/camera/drawable/TextDrawable.java
class TextDrawable(
    private val text: CharSequence
    , typeFace: Typeface
    , textSize: Float
    , color: Int
    , strokeWidth: Float = 0F
    , private val canvasWidth: Int = 0
    , private val canvasHeight: Int = 0
    , private val ignoreLowercase: Boolean = false
): BaseTextDrawable() {
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
        if (canvasWidth > 0) {
            adjustTextSize(canvasWidth)
        }

        _intrinsicWidth = calculateIntrinsicWidth()
        _intrinsicHeight = Paint.FontMetricsInt().ascent
    }

    private fun adjustTextSize(maxWidth: Int) {
        while (calculateIntrinsicWidth() > maxWidth) {
            paint.textSize -= 1
        }
    }

    private fun calculateIntrinsicWidth(): Int {
        return (paint.measureText(text, 0, text.length) + 0.5).toInt()
    }

    private fun calculateX(): Float {
        if (canvasWidth > 0) {
            return canvasWidth / 2f
        }

        return bounds.centerX().toFloat()
    }

    private fun calculateY(): Float {
        val isUppercase = text.toString().uppercase() == text.toString()
        val heightModifier = if (!ignoreLowercase && !isUppercase) {
            (paint.descent() + paint.ascent()) / 2
        } else {
            paint.ascent() / 2
        }

        if (canvasHeight > 0) {
            return canvasHeight / 2f - heightModifier
        }

        return bounds.centerY().toFloat() - heightModifier
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

    override fun getPaths(): List<Path> {
        val path = Path()
        paint.getTextPath(text.toString(), 0, text.length, calculateX(), calculateY(), path)
        return listOf(path)
    }

    override fun draw(canvas: Canvas) {
        //canvas.drawText(text, 0, text.length, bounds.exactCenterX(), bounds.exactCenterY(), paint)

        canvas.drawText(text, 0, text.length, calculateX(), calculateY(), paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }
}