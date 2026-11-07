package dev.renkinProject.renkin.vector

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import dev.renkinProject.renkin.drawable.ImageVectorDrawable
import dev.renkinProject.renkin.vector.brush.ReferenceBrush
import dev.renkinProject.renkin.vector.brush.SolidColorShader
import dev.renkinProject.renkin.vector.brush.VectorGradientBrush

class VectorRenderer(private val imageVector: ImageVector) {
    private val matrixStack = ArrayDeque<Matrix>()
    private var currentMatrix = Matrix()

    fun renderToCanvas(
        canvas: Canvas,
        nonScalingStroke: Boolean = true,
        targetWidth: Int = canvas.width,
        targetHeight: Int = canvas.height
    ) {
        if (nonScalingStroke) {
            renderNonScalingStroke(canvas, targetWidth, targetHeight)
        } else {
            render(canvas, targetWidth, targetHeight)
        }
    }

    private fun render(canvas: Canvas, targetWidth: Int, targetHeight: Int) {
        val matrix = Matrix()
        matrix.preScale(
            targetWidth / imageVector.viewportWidth,
            targetHeight / imageVector.viewportHeight
        )

        canvas.concat(matrix)

        renderGroup(canvas, imageVector.root)
    }

    private fun renderGroup(canvas: Canvas, group: VectorGroup) {
        canvas.save()
        canvas.translate(group.translationX, group.translationY)
        canvas.rotate(group.rotation, group.pivotX, group.pivotY)
        canvas.scale(group.scaleX, group.scaleY, group.pivotX, group.pivotY)

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
        val androidPath = path.pathData.toPath().asAndroidPath()
        androidPath.fillType = convertFillType(path.pathFillType)
        drawPath(canvas, androidPath, path, shaderMatrix = null)
    }

    private fun renderNonScalingStroke(canvas: Canvas, targetWidth: Int, targetHeight: Int) {
        currentMatrix.preScale(
            targetWidth / imageVector.viewportWidth,
            targetHeight / imageVector.viewportHeight
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
        newPath.fillType = convertFillType(path.pathFillType)

        // The path is pre-transformed here instead of the canvas, so gradients need the same matrix.
        drawPath(canvas, newPath, path, shaderMatrix = currentMatrix)
    }

    /**
     * Fill and stroke are drawn independently, like VectorDrawable itself does — a path may
     * carry both. SVG-to-vector converters (Lawnicons among others) also emit a TRANSPARENT
     * strokeColor with strokeWidth="1" on plain filled paths; the old either/or paint choice
     * saw the non-zero width, drew only the invisible stroke and dropped the fill entirely,
     * blanking every icon of such packs.
     */
    private fun drawPath(canvas: Canvas, androidPath: Path, path: VectorPath, shaderMatrix: Matrix?) {
        getFillPaint(path, shaderMatrix)?.let { canvas.drawPath(androidPath, it) }
        getStrokePaint(path, shaderMatrix)?.let { canvas.drawPath(androidPath, it) }
    }

    /** Colour or gradient shader of [brush] at [alpha]; null when nothing would be painted. */
    private fun paintFor(brush: Brush?, alpha: Float, shaderMatrix: Matrix?): Paint? {
        if (brush is VectorGradientBrush) {
            val paintAlpha = (alpha * 255).toInt()
            if (paintAlpha <= 0) return null
            return Paint().apply {
                color = android.graphics.Color.WHITE
                this.alpha = paintAlpha
                shader = brush.shader(shaderMatrix)
            }
        }
        val color = convertColor(brush)
        val paintAlpha = (Color(color).alpha * alpha * 255).toInt()
        if (paintAlpha <= 0) return null
        return Paint().apply {
            this.color = color
            this.alpha = paintAlpha
        }
    }

    private fun getStrokePaint(path: VectorPath, shaderMatrix: Matrix?): Paint? {
        if (path.strokeLineWidth <= 0f) return null
        val paint = paintFor(path.stroke, path.strokeAlpha, shaderMatrix) ?: return null
        paint.strokeCap = convertCap(path.strokeLineCap)
        paint.strokeJoin = convertJoin(path.strokeLineJoin)
        paint.strokeWidth = path.strokeLineWidth
        paint.strokeMiter = path.strokeLineMiter
        paint.style = Paint.Style.STROKE
        paint.flags = Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG

        return paint
    }

    private fun getFillPaint(path: VectorPath, shaderMatrix: Matrix?): Paint? {
        val paint = paintFor(path.fill, path.fillAlpha, shaderMatrix) ?: return null
        paint.style = Paint.Style.FILL
        paint.flags = Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG

        return paint
    }

    private fun convertFillType(fillType: PathFillType): Path.FillType =
        if (fillType == PathFillType.EvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING

    private fun convertColor(brush: Brush?): Int {
        if (brush == null)
            return Color.Unspecified.toArgb()

        if (brush is ReferenceBrush) {
            if (brush.shaderBrush is SolidColorShader) {
                return brush.shaderBrush.color.toArgb()
            }
        }

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
        fun ImageVectorDrawable.renderToCanvas(
            canvas: Canvas,
            nonScalingStroke: Boolean = true,
            targetWidth: Int = canvas.width,
            targetHeight: Int = canvas.height
        ) {
            this.toImageVector().renderToCanvas(canvas, nonScalingStroke, targetWidth, targetHeight)
        }

        fun ImageVector.renderToCanvas(
            canvas: Canvas,
            nonScalingStroke: Boolean = true,
            targetWidth: Int = canvas.width,
            targetHeight: Int = canvas.height
        ) {
            val renderer = VectorRenderer(this)
            renderer.renderToCanvas(canvas, nonScalingStroke, targetWidth, targetHeight)
        }
    }
}
