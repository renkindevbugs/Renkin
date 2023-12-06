package com.kaanelloed.iconeration.vector

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath

class VectorRenderer(private val imageVector: ImageVector) {
    fun renderToCanvas(canvas: Canvas) {
        val matrix = Matrix()
        matrix.preScale(canvas.height / imageVector.viewportHeight, canvas.height / imageVector.viewportHeight)

        canvas.concat(matrix)

        renderGroup(canvas, imageVector.root)
    }

    private fun renderGroup(canvas: Canvas, group: VectorGroup) {
        canvas.scale(group.scaleX, group.scaleY)
        canvas.translate(group.translationX, group.translationY)
        canvas.rotate(group.rotation)

        for (child in group) {
            if (child is VectorGroup) {
                renderGroup(canvas, child)
            }

            if (child is VectorPath) {
                renderPath(canvas, child)
            }
        }

        canvas.save()
    }

    private fun renderPath(canvas: Canvas, path: VectorPath) {
        val paint = Paint()
        paint.color = convertColor(path.stroke)
        paint.alpha = (path.strokeAlpha * 255).toInt()
        paint.strokeCap = convertCap(path.strokeLineCap)
        paint.strokeJoin = convertJoin(path.strokeLineJoin)
        paint.strokeWidth = path.strokeLineWidth
        paint.strokeMiter = path.strokeLineMiter
        paint.style = Paint.Style.STROKE
        paint.flags = Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG

        canvas.drawPath(path.pathData.toPath().asAndroidPath(), paint)
    }

    private fun convertColor(brush: Brush?): Int {
        if (brush == null)
            return Color.Unspecified.toArgb()

        if (brush !is SolidColor)
            return Color.Unspecified.toArgb()

        return brush.value.toArgb()
    }

    private fun convertCap(strokeCap: StrokeCap): Paint.Cap {
        return when (strokeCap) {
            StrokeCap.Butt -> Paint.Cap.BUTT
            StrokeCap.Round -> Paint.Cap.ROUND
            StrokeCap.Square -> Paint.Cap.SQUARE
            else -> Paint.Cap.BUTT
        }
    }

    private fun convertJoin(strokeJoin: StrokeJoin): Paint.Join {
        return when (strokeJoin) {
            StrokeJoin.Miter -> Paint.Join.MITER
            StrokeJoin.Round -> Paint.Join.ROUND
            StrokeJoin.Bevel -> Paint.Join.BEVEL
            else -> Paint.Join.MITER
        }
    }

    companion object {
        fun ImageVector.renderToCanvas(canvas: Canvas) {
            val renderer = VectorRenderer(this)
            renderer.renderToCanvas(canvas)
        }
    }
}