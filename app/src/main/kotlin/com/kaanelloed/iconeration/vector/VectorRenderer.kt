package com.kaanelloed.iconeration.vector

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
    private val matrixStack = ArrayDeque<Matrix>()
    private var currentMatrix = Matrix()
    fun renderToCanvas(canvas: Canvas, nonScalingStroke: Boolean = true) {
        if (nonScalingStroke) {
            renderNonScalingStroke(canvas)
        } else {
            render(canvas)
        }
    }

    private fun render(canvas: Canvas) {
        val matrix = Matrix()
        matrix.preScale(
            canvas.width / imageVector.viewportWidth,
            canvas.height / imageVector.viewportHeight
        )

        canvas.concat(matrix)

        renderGroup(canvas, imageVector.root)
    }

    private fun renderGroup(canvas: Canvas, group: VectorGroup) {
        canvas.save()
        canvas.translate(group.translationX, group.translationY)
        canvas.rotate(group.rotation, group.pivotX, group.pivotY)
        canvas.scale(group.scaleX, group.scaleY, -group.pivotX, -group.pivotY)

        for (child in group) {
            if (child is VectorGroup) {
                renderGroup(canvas, child)
            }

            if (child is VectorPath) {
                renderPath(canvas, child)
            }
        }

        canvas.restore()
    }

    private fun renderPath(canvas: Canvas, path: VectorPath) {
        canvas.drawPath(path.pathData.toPath().asAndroidPath(), getPaint(path))
    }

    private fun renderNonScalingStroke(canvas: Canvas) {
        currentMatrix.preScale(
            canvas.width / imageVector.viewportWidth,
            canvas.height / imageVector.viewportHeight
        )
        matrixStack.addLast(currentMatrix)

        renderNonScalingStrokeInGroup(canvas, imageVector.root)
    }

    private fun renderNonScalingStrokeInGroup(canvas: Canvas, group: VectorGroup) {
        val groupMatrix = Matrix()
        groupMatrix.postConcat(currentMatrix)
        groupMatrix.preTranslate(group.translationX, group.translationY)
        groupMatrix.preRotate(group.rotation, group.pivotX, group.pivotY)
        groupMatrix.preScale(group.scaleX, group.scaleY, group.pivotX, group.pivotY)

        matrixStack.addLast(groupMatrix)
        currentMatrix = groupMatrix

        for (child in group) {
            if (child is VectorGroup) {
                renderNonScalingStrokeInGroup(canvas, child)
            }

            if (child is VectorPath) {
                renderNonScalingStrokeInPath(canvas, child)
            }
        }

        matrixStack.removeLast()
        currentMatrix = matrixStack.last()
    }

    private fun renderNonScalingStrokeInPath(canvas: Canvas, path: VectorPath) {
        val androidPath = path.pathData.toPath().asAndroidPath()
        val newPath = Path()

        androidPath.transform(currentMatrix, newPath)

        canvas.drawPath(newPath, getPaint(path))
    }

    private fun getPaint(path: VectorPath): Paint {
        val paint = Paint()
        paint.color = convertColor(path.stroke)
        paint.alpha = (path.strokeAlpha * 255).toInt()
        paint.strokeCap = convertCap(path.strokeLineCap)
        paint.strokeJoin = convertJoin(path.strokeLineJoin)
        paint.strokeWidth = path.strokeLineWidth
        paint.strokeMiter = path.strokeLineMiter
        paint.style = Paint.Style.STROKE
        paint.flags = Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG

        return paint
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
        fun MutableImageVector.renderToCanvas(canvas: Canvas) {
            this.toImageVector().renderToCanvas(canvas)
        }

        fun ImageVector.renderToCanvas(canvas: Canvas) {
            val renderer = VectorRenderer(this)
            renderer.renderToCanvas(canvas)
        }
    }
}