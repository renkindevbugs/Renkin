package dev.renkinProject.renkin.drawable

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
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
import dev.renkinProject.renkin.vector.VectorEditor.Companion.center
import dev.renkinProject.renkin.vector.VectorEditor.Companion.applyModifierTransform
import dev.renkinProject.renkin.vector.VectorEditor.Companion.applyViewportInset
import dev.renkinProject.renkin.vector.VectorEditor.Companion.resizeTo
import dev.renkinProject.renkin.vector.VectorExporter.Companion.toXml
import dev.renkinProject.renkin.vector.VectorRenderer.Companion.renderToCanvas

class ImageVectorDrawable(imageVector: ImageVector): IconPackDrawable() {
    var name: String = imageVector.name
    var defaultWidth: Dp = imageVector.defaultWidth
    var defaultHeight: Dp = imageVector.defaultHeight
    var viewportWidth: Float = imageVector.viewportWidth
    var viewportHeight: Float = imageVector.viewportHeight
    var tintColor: Color = imageVector.tintColor
    var tintBlendMode: BlendMode = imageVector.tintBlendMode
    var autoMirror: Boolean = imageVector.autoMirror
    var root: MutableVectorGroup = MutableVectorGroup(imageVector.root)

    /** A detached mutable copy for editors/exporters that transform vectors in place. */
    fun deepCopy(): ImageVectorDrawable = ImageVectorDrawable(toImageVector())

    /**
     * Applies the Modifier tab's canvas-space position and scale directly to a detached vector.
     * Baking the transform into path coordinates keeps it resolution-independent without
     * persisting helper groups that the path editor would have to flatten again after reload.
     */
    fun withModifierTransform(scale: Float, offsetX: Float, offsetY: Float): ImageVectorDrawable {
        return deepCopy().applyModifierTransform(scale, offsetX, offsetY)
    }

    /** Converts an InsetIconDrawable's margins into an equivalent vector group. */
    fun withViewportInset(left: Float, top: Float, right: Float, bottom: Float): ImageVectorDrawable? {
        val scaleX = 1f - left - right
        val scaleY = 1f - top - bottom
        if (scaleX <= 0f || scaleY <= 0f) return null
        return deepCopy().applyViewportInset(left, top, scaleX, scaleY)
    }

    /** Raster fallback after vector-safe modifiers, without re-centering away a user offset. */
    fun toModifierBitmap(size: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        renderToCanvas(Canvas(bitmap), nonScalingStroke = false, targetWidth = size, targetHeight = size)
        return bitmap
    }

    private fun newBuilder(): ImageVector.Builder = ImageVector.Builder(
        name,
        defaultWidth,
        defaultHeight,
        viewportWidth,
        viewportHeight,
        tintColor,
        tintBlendMode,
        autoMirror
    )

    fun toImageVector(): ImageVector {
        val builder = newBuilder()
        addRootChildren(builder)
        return builder.build()
    }

    private fun addRootChildren(builder: ImageVector.Builder) {
        for (child in root.children) {
            if (child is MutableVectorGroup) {
                toVectorGroup(builder, child)
            }

            if (child is MutableVectorPath) {
                toVectorPath(builder, child)
            }
        }
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
        val target = bounds
        if (target.width() <= 0 || target.height() <= 0) {
            this.renderToCanvas(canvas)
            return
        }

        // Wrappers such as InsetDrawable communicate their scale through child bounds. The
        // custom renderer previously ignored those bounds and always used the full canvas,
        // so raster-only modifiers made inset vectors much larger than their Compose preview.
        val checkpoint = canvas.save()
        canvas.translate(target.left.toFloat(), target.top.toFloat())
        canvas.clipRect(0, 0, target.width(), target.height())
        this.renderToCanvas(canvas, targetWidth = target.width(), targetHeight = target.height())
        canvas.restoreToCount(checkpoint)
    }

    // The custom vector renderer ignores alpha / colour filter, so these are no-ops rather
    // than throwing — the platform may call them during the draw pipeline.
    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    @Composable
    override fun getPainter(): Painter {
        return rememberVectorPainter(toImageVector())
    }

    override fun toBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Rasterise a copy: resizeTo/center mutate the vector in place, and this drawable also
        // backs the live preview painter — mutating it here would shift/zoom the previewed icon
        // further on every rasterisation (the Position tool and the external-editor hand-off
        // both call toBitmap on the previewed icon).
        val copy = deepCopy()
        copy.resizeTo(256F, 256F).center()
        copy.renderToCanvas(canvas)
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
