package dev.alembiconsProject.alembicons.drawable

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.Dp
import dev.alembiconsProject.alembicons.vector.VectorEditor.Companion.center
import dev.alembiconsProject.alembicons.vector.VectorEditor.Companion.resizeTo
import dev.alembiconsProject.alembicons.vector.VectorExporter.Companion.toXml
import dev.alembiconsProject.alembicons.vector.VectorRenderer.Companion.renderToCanvas

class ImageVectorDrawable(imageVector: ImageVector): Drawable(), IconPackDrawable {
    var name: String = imageVector.name
    var defaultWidth: Dp = imageVector.defaultWidth
    var defaultHeight: Dp = imageVector.defaultHeight
    var viewportWidth: Float = imageVector.viewportWidth
    var viewportHeight: Float = imageVector.viewportHeight
    var tintColor: Color = imageVector.tintColor
    var tintBlendMode: BlendMode = imageVector.tintBlendMode
    var autoMirror: Boolean = imageVector.autoMirror
    var root: MutableVectorGroup = MutableVectorGroup(imageVector.root)

    fun toImageVector(): ImageVector {
        val builder = ImageVector.Builder(
            name,
            defaultWidth,
            defaultHeight,
            viewportWidth,
            viewportHeight,
            tintColor,
            tintBlendMode,
            autoMirror
        )

        for (child in root.children) {
            if (child is MutableVectorGroup) {
                toVectorGroup(builder, child)
            }

            if (child is MutableVectorPath) {
                toVectorPath(builder, child)
            }
        }

        return builder.build()
    }

    private fun toVectorGroup(builder: ImageVector.Builder, mutableVectorGroup: MutableVectorGroup) {
        builder.addGroup(
            mutableVectorGroup.name,
            mutableVectorGroup.rotation,
            mutableVectorGroup.pivotX,
            mutableVectorGroup.pivotY,
            mutableVectorGroup.scaleX,
            mutableVectorGroup.scaleY,
            mutableVectorGroup.translationX,
            mutableVectorGroup.translationY,
            mutableVectorGroup.clipPathData
        )

        for (child in mutableVectorGroup.children) {
            if (child is MutableVectorGroup) {
                toVectorGroup(builder, child)
            }

            if (child is MutableVectorPath) {
                toVectorPath(builder, child)
            }
        }

        builder.clearGroup()
    }

    private fun toVectorPath(builder: ImageVector.Builder, mutableVectorPath: MutableVectorPath) {
        builder.addPath(
            mutableVectorPath.pathData,
            mutableVectorPath.pathFillType,
            mutableVectorPath.name,
            mutableVectorPath.fill,
            mutableVectorPath.fillAlpha,
            mutableVectorPath.stroke,
            mutableVectorPath.strokeAlpha,
            mutableVectorPath.strokeLineWidth,
            mutableVectorPath.strokeLineCap,
            mutableVectorPath.strokeLineJoin,
            mutableVectorPath.strokeLineMiter,
            mutableVectorPath.trimPathStart,
            mutableVectorPath.trimPathEnd,
            mutableVectorPath.trimPathOffset
        )
    }

    override fun draw(canvas: Canvas) {
        this.renderToCanvas(canvas)
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

    @Composable
    override fun getPainter(): Painter {
        return rememberVectorPainter(toImageVector())
    }

    override fun toBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        this.resizeTo(256F, 256F).center()
        this.renderToCanvas(canvas)
        return bmp
    }

    override fun toDbString(): String {
        val bytes = toImageVector().toXml()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

sealed class MutableVectorNode

class MutableVectorGroup(vectorGroup: VectorGroup): MutableVectorNode() {
    var name: String = vectorGroup.name
    var rotation: Float = vectorGroup.rotation
    var pivotX: Float = vectorGroup.pivotX
    var pivotY: Float = vectorGroup.pivotY
    var scaleX: Float = vectorGroup.scaleX
    var scaleY: Float = vectorGroup.scaleY
    var translationX: Float = vectorGroup.translationX
    var translationY: Float = vectorGroup.translationY
    val clipPathData: MutableList<PathNode> = vectorGroup.clipPathData.toMutableList()
    val children: MutableList<MutableVectorNode> = mutableListOf()

    init {
        for (child in vectorGroup) {
            if (child is VectorGroup) {
                children.add(MutableVectorGroup(child))
            }

            if (child is VectorPath) {
                children.add(MutableVectorPath(child))
            }
        }
    }
}

class MutableVectorPath(vectorPath: VectorPath): MutableVectorNode() {
    var name: String = vectorPath.name
    val pathData: MutableList<PathNode> = vectorPath.pathData.toMutableList()
    var pathFillType: PathFillType = vectorPath.pathFillType
    var fill: Brush? = vectorPath.fill
    var fillAlpha: Float = vectorPath.fillAlpha
    var stroke: Brush? = vectorPath.stroke
    var strokeAlpha: Float = vectorPath.strokeAlpha
    var strokeLineWidth: Float = vectorPath.strokeLineWidth
    var strokeLineCap: StrokeCap = vectorPath.strokeLineCap
    var strokeLineJoin: StrokeJoin = vectorPath.strokeLineJoin
    var strokeLineMiter: Float = vectorPath.strokeLineMiter
    var trimPathStart: Float = vectorPath.trimPathStart
    var trimPathEnd: Float = vectorPath.trimPathEnd
    var trimPathOffset: Float = vectorPath.trimPathOffset
}

fun ImageVector.toImageVectorDrawable(): ImageVectorDrawable {
    return ImageVectorDrawable(this)
}