package com.kaanelloed.iconeration.vector

import android.graphics.RectF
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.dp
import com.kaanelloed.iconeration.drawable.ImageVectorDrawable
import com.kaanelloed.iconeration.drawable.MutableVectorGroup
import com.kaanelloed.iconeration.drawable.MutableVectorPath
import com.kaanelloed.iconeration.vector.NodeEditor.Companion.rotate
import com.kaanelloed.iconeration.vector.NodeEditor.Companion.scale
import com.kaanelloed.iconeration.vector.NodeEditor.Companion.translate
import com.kaanelloed.iconeration.vector.PathConverter.Companion.isRelative
import com.kaanelloed.iconeration.vector.PathConverter.Companion.toAbsolute
import com.kaanelloed.iconeration.vector.brush.ReferenceBrush
import com.kaanelloed.iconeration.vector.brush.SolidColorShader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

class VectorEditor internal constructor(private val mutableVector: ImageVectorDrawable) {
    private fun resizeTo(width: Float, height: Float): ImageVectorDrawable {
        val scaleX = width / mutableVector.viewportWidth
        val scaleY = height / mutableVector.viewportHeight

        mutableVector.viewportWidth = width
        mutableVector.viewportHeight = height

        mutableVector.defaultWidth = width.dp
        mutableVector.defaultHeight = height.dp

        resizeGroup(mutableVector.root, scaleX, scaleY, 0F, 0F)

        return mutableVector
    }

    private fun resizeGroup(group: MutableVectorGroup, scaleX: Float, scaleY: Float, translateX: Float, translateY: Float) {
        group.translationX *= scaleX
        group.translationY *= scaleY

        for (child in group.children) {
            if (child is MutableVectorGroup) {
                resizeGroup(child, scaleX, scaleY, translateX, translateY)
            }

            if (child is MutableVectorPath) {
                child.strokeLineWidth *= scaleX
                scalePath(child, scaleX, scaleY, translateX, translateY)
            }
        }
    }

    private fun scaleAtCenter(scale: Float): ImageVectorDrawable {
        if (scale == 1f) return mutableVector

        return scaleAtCenter(scale, scale)
    }

    private fun scaleAtCenter(scaleX: Float, scaleY: Float): ImageVectorDrawable {
        val pivotX = mutableVector.viewportWidth / 2
        val pivotY = mutableVector.viewportHeight / 2

        scaleGroup(mutableVector.root, scaleX, scaleY, pivotX, pivotY)
        center()

        return mutableVector
    }

    private fun scaleGroup(group: MutableVectorGroup, scaleX: Float, scaleY: Float, pivotX: Float, pivotY: Float) {
        for (child in group.children) {
            if (child is MutableVectorGroup) {
                scaleGroup(child, scaleX, scaleY, pivotX, pivotY)
            }

            if (child is MutableVectorPath) {
                scalePath(child, scaleX, scaleY, pivotX, pivotY)
            }
        }
    }

    private fun scalePath(path: MutableVectorPath, scaleX: Float, scaleY: Float, pivotX: Float, pivotY: Float) {
        for (i in path.pathData.indices) {
            var node = path.pathData[i]

            if (i == 0 && node.isRelative()) {
                node = node.toAbsolute()
            }

            path.pathData[i] = node.scale(scaleX, scaleY, pivotX, pivotY)
        }
    }

    private fun translateGroup(group: MutableVectorGroup, translateX: Float, translateY: Float) {
        for (child in group.children) {
            if (child is MutableVectorGroup) {
                translateGroup(child, translateX, translateY)
            }

            if (child is MutableVectorPath) {
                translatePath(child, translateX, translateY)
            }
        }
    }

    private fun translatePath(path: MutableVectorPath, translateX: Float, translateY: Float) {
        for (i in path.pathData.indices) {
            var node = path.pathData[i]

            if (i == 0 && node.isRelative()) {
                node = node.toAbsolute()
            }

            path.pathData[i] = node.translate(translateX, translateY)
        }
    }

    private fun rotatePath(path: MutableVectorPath, rotation: Float, pivotX: Float, pivotY: Float) {
        for (i in path.pathData.indices) {
            var node = path.pathData[i]

            if (i == 0 && node.isRelative()) {
                node = node.toAbsolute()
            }

            path.pathData[i] = node.rotate(rotation, pivotX, pivotY)
        }
    }

    private fun roundAlpha(): ImageVectorDrawable {
        roundGroupAlpha(mutableVector.root)
        return mutableVector
    }

    private fun roundGroupAlpha(group: MutableVectorGroup) {
        for (child in group.children) {
            if (child is MutableVectorGroup) {
                roundGroupAlpha(child)
            }

            if (child is MutableVectorPath) {
                roundPathAlpha(child)
            }
        }
    }

    private fun roundPathAlpha(path: MutableVectorPath) {
        path.fillAlpha = round(path.fillAlpha)
        path.strokeAlpha = round(path.strokeAlpha)
    }

    private fun resizeAndCenter(): ImageVectorDrawable {
        if (mutableVector.viewportWidth == mutableVector.viewportHeight) {
            return mutableVector
        }

        return if (mutableVector.viewportWidth > mutableVector.viewportHeight) {
            resizeAndCenter(mutableVector.viewportWidth, mutableVector.viewportWidth)
        } else {
            resizeAndCenter(mutableVector.viewportHeight, mutableVector.viewportHeight)
        }
    }

    private fun resizeAndCenter(width: Float, height: Float): ImageVectorDrawable {
        val translateX = (width - mutableVector.viewportWidth) / 2
        val translateY = (height - mutableVector.viewportHeight) / 2

        mutableVector.viewportWidth = width
        mutableVector.viewportHeight = height

        mutableVector.defaultWidth = width.dp
        mutableVector.defaultHeight = height.dp

        translateGroup(mutableVector.root, translateX, translateY)

        return mutableVector
    }

    private fun changeViewPort(width: Float, height: Float): ImageVectorDrawable {
        val translateX = (width - mutableVector.viewportWidth) / 2
        val translateY = (height - mutableVector.viewportHeight) / 2

        mutableVector.viewportWidth = width
        mutableVector.viewportHeight = height

        translateGroup(mutableVector.root, translateX, translateY)

        return mutableVector
    }

    private fun applyAndRemoveGroup(): ImageVectorDrawable {
        val paths = applyGroup(mutableVector.root)
        mutableVector.root.children.clear()
        mutableVector.root.children.addAll(paths)

        return mutableVector
    }

    private fun applyGroup(group: MutableVectorGroup): MutableList<MutableVectorPath> {
        val newChildren = mutableListOf<MutableVectorPath>()

        for (child in group.children) {
            if (child is MutableVectorGroup) {
                val paths = applyGroup(child)

                for (path in paths) {
                    applyGroup(group, path)
                    newChildren.add(path)
                }
            }

            if (child is MutableVectorPath) {
                applyGroup(group, child)
                newChildren.add(child)
            }
        }

        return newChildren
    }

    private fun applyGroup(group: MutableVectorGroup, path: MutableVectorPath) {
        translatePath(path, group.translationX, group.translationY)
        rotatePath(path, group.rotation, group.pivotX, group.pivotY)
        scalePath(path, group.scaleX, group.scaleY, group.pivotX, group.pivotY)
    }

    private fun center(): ImageVectorDrawable {
        val bounds = getBounds()
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top

        val translateX = (mutableVector.viewportWidth - width) / 2 - bounds.left
        val translateY = (mutableVector.viewportHeight - height) / 2 - bounds.top

        translateGroup(mutableVector.root, translateX, translateY)

        return mutableVector
    }

    private fun getBounds(): Rect {
        return getGroupBounds(mutableVector.root)
    }

    private fun getGroupBounds(group: MutableVectorGroup): Rect {
        var rect = Rect(Float.MAX_VALUE, Float.MAX_VALUE, Float.MIN_VALUE , Float.MIN_VALUE)

        for (child in group.children) {
            if (child is MutableVectorGroup) {
                rect = unionRect(rect, getGroupBounds(child))
            }

            if (child is MutableVectorPath) {
                rect = unionRect(rect, getPathBounds(child))
            }
        }

        return rect
    }

    private fun getPathBounds(path: MutableVectorPath): Rect {
        return path.pathData.toPath().getBounds()
    }

    private fun unionRect(rect1: Rect, rect2: Rect): Rect {
        val left = min(rect1.left, rect2.left)
        val top = min(rect1.top, rect2.top)
        val right = max(rect1.right, rect2.right)
        val bottom = max(rect1.bottom, rect2.bottom)

        return Rect(left, top, right, bottom)
    }

    private fun inset(rect: Rect) {
        val scaleX = (rect.left + rect.right) / mutableVector.viewportWidth
        val scaleY = (rect.top + rect.bottom) / mutableVector.viewportHeight

        val pivotX = mutableVector.viewportWidth / 2
        val pivotY = mutableVector.viewportHeight / 2

        scaleGroup(mutableVector.root, scaleX, scaleY, pivotX, pivotY)
    }

    companion object {
        fun ImageVectorDrawable.scaleAtCenter(scale: Float): ImageVectorDrawable {
            val editor = VectorEditor(this)
            return editor.scaleAtCenter(scale)
        }

        fun ImageVectorDrawable.resizeTo(width: Float, height: Float): ImageVectorDrawable {
            val editor = VectorEditor(this)
            return editor.resizeTo(width, height)
        }

        fun ImageVectorDrawable.roundAlpha(): ImageVectorDrawable {
            val editor = VectorEditor(this)
            return editor.roundAlpha()
        }

        fun ImageVectorDrawable.resizeAndCenter(): ImageVectorDrawable {
            val editor = VectorEditor(this)
            return editor.resizeAndCenter()
        }

        fun ImageVectorDrawable.changeViewPort(width: Float, height: Float): ImageVectorDrawable {
            val editor = VectorEditor(this)
            return editor.changeViewPort(width, height)
        }

        fun ImageVectorDrawable.applyAndRemoveGroup(): ImageVectorDrawable {
            val editor = VectorEditor(this)
            return editor.applyAndRemoveGroup()
        }

        fun ImageVectorDrawable.getBounds(): Rect {
            val editor = VectorEditor(this)
            return editor.getBounds()
        }

        fun ImageVectorDrawable.center(): ImageVectorDrawable {
            val editor = VectorEditor(this)
            return editor.center()
        }

        fun MutableVectorGroup.editStrokePaths(stroke: Float) {
            for (child in this.children) {
                if (child is MutableVectorGroup) {
                    child.editStrokePaths(stroke)
                }

                if (child is MutableVectorPath) {
                    child.strokeLineWidth = stroke
                }
            }
        }

        fun MutableVectorGroup.editPaths(stroke: Float, fillColor: Brush, strokeColor: Brush) {
            for (child in this.children) {
                if (child is MutableVectorGroup) {
                    child.editPaths(stroke, fillColor, strokeColor)
                }

                if (child is MutableVectorPath) {
                    child.strokeLineWidth = stroke
                    child.fill = fillColor
                    child.stroke = strokeColor
                }
            }
        }

        fun MutableVectorGroup.editPathColors(fillColor: Brush, strokeColor: Brush) {
            for (child in this.children) {
                if (child is MutableVectorGroup) {
                    child.editPathColors(fillColor, strokeColor)
                }

                if (child is MutableVectorPath) {
                    child.fill = fillColor
                    child.stroke = strokeColor
                }
            }
        }

        fun MutableVectorGroup.editStrokePaths(strokeColor: Brush) {
            for (child in this.children) {
                if (child is MutableVectorGroup) {
                    child.editStrokePaths(strokeColor)
                }

                if (child is MutableVectorPath) {
                    child.stroke = strokeColor
                }
            }
        }

        fun MutableVectorGroup.setReferenceColorPaths(color: Brush) {
            for (child in this.children) {
                if (child is MutableVectorGroup) {
                    child.setReferenceColorPaths(color)
                }

                if (child is MutableVectorPath) {
                    if (child.strokeLineWidth == 0f) {
                        child.stroke = null
                        child.fill = color
                    } else {
                        child.stroke = color
                        child.fill = null
                    }

                }
            }
        }

        fun MutableVectorGroup.editFillPaths(fillColor: Brush) {
            for (child in this.children) {
                if (child is MutableVectorGroup) {
                    child.editFillPaths(fillColor)
                }

                if (child is MutableVectorPath) {
                    child.fill = fillColor
                }
            }
        }

        fun MutableVectorGroup.removeFillReference() {
            for (child in this.children) {
                if (child is MutableVectorGroup) {
                    child.removeFillReference()
                }

                if (child is MutableVectorPath) {
                    if (child.fill is ReferenceBrush) {
                        val ref = child.fill as ReferenceBrush
                        if (ref.shaderBrush is SolidColorShader)
                            child.fill = SolidColor(ref.shaderBrush.color)
                        else
                            child.fill = SolidColor(Color.Unspecified)
                    }
                }
            }
        }

        fun MutableVectorGroup.removeStrokeReference() {
            for (child in this.children) {
                if (child is MutableVectorGroup) {
                    child.removeStrokeReference()
                }

                if (child is MutableVectorPath) {
                    if (child.stroke is ReferenceBrush) {
                        val ref = child.stroke as ReferenceBrush
                        if (ref.shaderBrush is SolidColorShader)
                            child.stroke = SolidColor(ref.shaderBrush.color)
                        else
                            child.stroke = SolidColor(Color.Unspecified)
                    }
                }
            }
        }

        fun ImageVectorDrawable.inset(rect: Rect) {
            val editor = VectorEditor(this)
            editor.inset(rect)
        }

        fun ImageVectorDrawable.inset(rect: RectF) {
            val left = rect.left * viewportWidth
            val right = rect.right * viewportWidth
            val top = rect.top * viewportHeight
            val bottom = rect.bottom * viewportHeight

            val dim = Rect(left, top, right, bottom)
            this.inset(dim)
        }
    }
}