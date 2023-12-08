package com.kaanelloed.iconeration.vector

import androidx.compose.ui.graphics.vector.PathNode
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

abstract class NodeEditor(val relative: Boolean) {
    abstract fun scale(scaleX: Float, scaleY: Float): PathNode
    abstract fun translate(translateX: Float, translateY: Float): PathNode
    abstract fun rotate(rotation: Float, pivotX: Float, pivotY: Float): PathNode

    companion object {
        fun PathNode.scale(scaleX: Float, scaleY: Float): PathNode {
            return this.getEditor().scale(scaleX, scaleY)
        }

        fun PathNode.scale(scaleX: Float, scaleY: Float, pivotX: Float, pivotY: Float): PathNode {
            if (pivotX == 0F && pivotY == 0F) {
                return this.getEditor().scale(scaleX, scaleY)
            }

            val translated = this.getEditor().translate(-pivotX, -pivotY)
            val scaled = translated.getEditor().scale(scaleX, scaleY)
            return scaled.getEditor().translate(pivotX, pivotY)
        }

        fun PathNode.translate(translateX: Float, translateY: Float): PathNode {
            return this.getEditor().translate(translateX, translateY)
        }

        fun PathNode.rotate(rotation: Float, pivotX: Float, pivotY: Float): PathNode {
            return this.getEditor().rotate(rotation, pivotX, pivotY)
        }

        private fun PathNode.getEditor(): NodeEditor {
            return when (this) {
                is PathNode.Close -> CloseEditor(this)
                is PathNode.RelativeMoveTo -> RelativeMoveToEditor(this)
                is PathNode.MoveTo -> MoveToEditor(this)
                is PathNode.RelativeLineTo -> RelativeLineToEditor(this)
                is PathNode.LineTo -> LineToEditor(this)
                is PathNode.RelativeHorizontalTo -> RelativeHorizontalToEditor(this)
                is PathNode.HorizontalTo -> HorizontalToEditor(this)
                is PathNode.RelativeVerticalTo -> RelativeVerticalToEditor(this)
                is PathNode.VerticalTo -> VerticalToEditor(this)
                is PathNode.RelativeCurveTo -> RelativeCurveToEditor(this)
                is PathNode.CurveTo -> CurveToEditor(this)
                is PathNode.RelativeReflectiveCurveTo -> RelativeReflectiveCurveToEditor(this)
                is PathNode.ReflectiveCurveTo -> ReflectiveCurveToEditor(this)
                is PathNode.RelativeQuadTo -> RelativeQuadToEditor(this)
                is PathNode.QuadTo -> QuadToEditor(this)
                is PathNode.RelativeReflectiveQuadTo -> RelativeReflectiveQuadToEditor(this)
                is PathNode.ReflectiveQuadTo -> ReflectiveQuadToEditor(this)
                is PathNode.RelativeArcTo -> RelativeArcToEditor(this)
                is PathNode.ArcTo -> ArcToEditor(this)
            }
        }
    }

    private class CloseEditor(private val node: PathNode.Close): NodeEditor(false) {
        override fun scale(scaleX: Float, scaleY: Float): PathNode { return node }

        override fun translate(translateX: Float, translateY: Float): PathNode { return node }

        override fun rotate(rotation: Float, pivotX: Float, pivotY: Float): PathNode { return node }
    }

    private abstract class BaseEditor(
        private val nodeX1: Float,
        private val nodeY1: Float,
        private val nodeX2: Float,
        private val nodeY2: Float,
        private val nodeX3: Float,
        private val nodeY3: Float,
        relative: Boolean
    ) : NodeEditor(relative) {
        override fun scale(scaleX: Float, scaleY: Float): PathNode {
            val x1 = nodeX1 * scaleX
            val y1 = nodeY1 * scaleY
            val x2 = nodeX2 * scaleX
            val y2 = nodeY2 * scaleY
            val x3 = nodeX3 * scaleX
            val y3 = nodeY3 * scaleY

            return createNode(x1, y1, x2, y2, x3, y3)
        }

        override fun translate(translateX: Float, translateY: Float): PathNode {
            return if (relative) {
                createNode(nodeX1, nodeY1, nodeX2, nodeY2, nodeX3, nodeY3)
            } else  {
                val x1 = nodeX1 + translateX
                val y1 = nodeY1 + translateY
                val x2 = nodeX2 + translateX
                val y2 = nodeY2 + translateY
                val x3 = nodeX3 + translateX
                val y3 = nodeY3 + translateY

                return createNode(x1, y1, x2, y2, x3, y3)
            }
        }

        override fun rotate(rotation: Float, pivotX: Float, pivotY: Float): PathNode {
            val rad = rotation * PI / 180
            val cos = cos(rad)
            val sin = sin(rad)

            val px = if (relative) 0F else pivotX
            val py = if (relative) 0F else pivotY

            val x1 = rotateX(nodeX1, nodeY1, px, py, cos, sin)
            val y1 = rotateY(nodeX1, nodeY1, px, py, cos, sin)
            val x2 = rotateX(nodeX2, nodeY2, px, py, cos, sin)
            val y2 = rotateY(nodeX2, nodeY2, px, py, cos, sin)
            val x3 = rotateX(nodeX3, nodeY3, px, py, cos, sin)
            val y3 = rotateY(nodeX3, nodeY3, px, py, cos, sin)

            return createNode(x1, y1, x2, y2, x3, y3)
        }

        private fun rotateX(nodeX: Float, nodeY: Float, pivotX: Float, pivotY: Float, cos: Double, sin: Double): Float {
            return (pivotX + (nodeX - pivotX) * cos - (nodeY - pivotY) * sin).toFloat()
        }

        private fun rotateY(nodeX: Float, nodeY: Float, pivotX: Float, pivotY: Float, cos: Double, sin: Double): Float {
            return (pivotY + (nodeX - pivotX) * sin + (nodeY - pivotY) * cos).toFloat()
        }

        abstract fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode
    }

    private class RelativeMoveToEditor(node: PathNode.RelativeMoveTo) :
        BaseEditor(node.dx, node.dy, 0F, 0F, 0F, 0F, true) {
        override fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode {
            return PathNode.RelativeMoveTo(x1, y1)
        }
    }

    private class MoveToEditor(node: PathNode.MoveTo) :
        BaseEditor(node.x, node.y, 0F, 0F, 0F, 0F, false) {
        override fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode {
            return  PathNode.MoveTo(x1, y1)
        }
    }

    private class RelativeLineToEditor(node: PathNode.RelativeLineTo) :
        BaseEditor(node.dx, node.dy, 0F, 0F, 0F, 0F, true) {
        override fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode {
            return PathNode.RelativeLineTo(x1, y1)
        }
    }

    private class LineToEditor(node: PathNode.LineTo) :
        BaseEditor(node.x, node.y, 0F, 0F, 0F, 0F, false) {
        override fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode {
            return PathNode.LineTo(x1, y1)
        }
    }

    private abstract class BaseOneDimensionEditor(
        private val nodeX: Float,
        private val nodeY: Float,
        relative: Boolean
    ) : BaseEditor(nodeX, nodeY, 0F, 0F, 0F, 0F, relative) {
        override fun rotate(rotation: Float, pivotX: Float, pivotY: Float): PathNode {
            val x1 = if (rotation == 180F) -nodeX else nodeX
            val y1 = if (rotation == 180F) -nodeY else nodeY

            return createNode(x1, y1, 0F, 0F, 0F, 0F)
        }
    }

    private class RelativeHorizontalToEditor(node: PathNode.RelativeHorizontalTo) :
        BaseOneDimensionEditor(node.dx, 0F, true) {
        override fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode {
            return PathNode.RelativeHorizontalTo(x1)
        }
    }

    private class HorizontalToEditor(node: PathNode.HorizontalTo) :
        BaseOneDimensionEditor(node.x, 0F, false) {
        override fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode {
            return PathNode.HorizontalTo(x1)
        }
    }

    private class RelativeVerticalToEditor(node: PathNode.RelativeVerticalTo) :
        BaseOneDimensionEditor(0F, node.dy, true) {
        override fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode {
            return PathNode.RelativeVerticalTo(y1)
        }
    }

    private class VerticalToEditor(node: PathNode.VerticalTo) :
        BaseOneDimensionEditor(0F, node.y, false) {
        override fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode {
            return PathNode.VerticalTo(y1)
        }
    }

    private class RelativeCurveToEditor(node: PathNode.RelativeCurveTo) :
        BaseEditor(node.dx1, node.dy1, node.dx2, node.dy2, node.dx3, node.dy3, true) {
        override fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode {
            return PathNode.RelativeCurveTo(x1, y1, x2, y2, x3, y3)
        }
    }

    private class CurveToEditor(node: PathNode.CurveTo) :
        BaseEditor(node.x1, node.y1, node.x2, node.y2, node.x3, node.y3, false) {
        override fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode {
            return PathNode.CurveTo(x1, y1, x2, y2, x3, y3)
        }
    }

    private class RelativeReflectiveCurveToEditor(node: PathNode.RelativeReflectiveCurveTo) :
        BaseEditor(node.dx1, node.dy1, node.dx2, node.dy2, 0F, 0F, true) {
        override fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode {
            return PathNode.RelativeReflectiveCurveTo(x1, y1, x2, y2)
        }
    }

    private class ReflectiveCurveToEditor(node: PathNode.ReflectiveCurveTo) :
        BaseEditor(node.x1, node.y1, node.x2, node.y2, 0F, 0F, false) {
        override fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode {
            return PathNode.ReflectiveCurveTo(x1, y1, x2, y2)
        }
    }

    private class RelativeQuadToEditor(node: PathNode.RelativeQuadTo) :
        BaseEditor(node.dx1, node.dy1, node.dx2, node.dy2, 0F, 0F, true) {
        override fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode {
            return PathNode.RelativeQuadTo(x1, y1, x2, y2)
        }
    }

    private class QuadToEditor(node: PathNode.QuadTo) :
        BaseEditor(node.x1, node.y1, node.x2, node.y2, 0F, 0F, false) {
        override fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode {
            return PathNode.QuadTo(x1, y1, x2, y2)
        }
    }

    private class RelativeReflectiveQuadToEditor(node: PathNode.RelativeReflectiveQuadTo) :
        BaseEditor(node.dx, node.dy, 0F, 0F, 0F, 0F, true) {
        override fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode {
            return PathNode.RelativeReflectiveQuadTo(x1, y1)
        }
    }

    private class ReflectiveQuadToEditor(node: PathNode.ReflectiveQuadTo) :
        BaseEditor(node.x, node.y, 0F, 0F, 0F, 0F, false) {
        override fun createNode(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PathNode {
            return PathNode.ReflectiveQuadTo(x1, y1)
        }
    }

    private abstract class BaseArcToEditor(
        private val horizontalEllipseRadius: Float,
        private val verticalEllipseRadius: Float,
        private val theta: Float,
        private val isMoreThanHalf: Boolean,
        private val isPositiveArc: Boolean,
        private val arcStartX: Float,
        private val arcStartY: Float,
        relative: Boolean
    ) : NodeEditor(relative) {
        override fun scale(scaleX: Float, scaleY: Float): PathNode {
            if (scaleX == 1F && scaleY == 1F) {
                return getDefaultNode()
            }

            val angle = PI * theta / 180.0
            val cos = cos(angle)
            val sin = sin(angle)

            val hRadius2 = horizontalEllipseRadius * horizontalEllipseRadius
            val vRadius2 = verticalEllipseRadius * verticalEllipseRadius
            val cos2 = cos * cos
            val sin2 = sin * sin
            val scaleX2 = scaleX * scaleX
            val scaleY2 = scaleY * scaleY

            val a = vRadius2 * scaleY2 * cos2 + hRadius2 * scaleY2 * sin2
            val b = 2 * scaleX * scaleY * cos * sin * (vRadius2 - hRadius2)
            val c = hRadius2 * scaleX2 * cos2 + vRadius2 * scaleX2 * sin2
            val f = -(hRadius2 * vRadius2 * scaleX2 * scaleY2)
            val b2 = b * b

            val det = b2 - 4 * a * c
            val val1 = sqrt((a - c) * (a - c) + b2)

            val newTheta = if (b != 0.0) atan((c - a - val1) / b) * 180 / PI else if (a < c) 0.0 else 90.0

            var newHRadius = horizontalEllipseRadius
            var newVRadius = verticalEllipseRadius
            if (det != 0.0) {
                newHRadius = (-sqrt(2 * det * f * ((a + c) + val1)) / det).toFloat()
                newVRadius = (-sqrt(2 * det * f * ((a + c) - val1)) / det).toFloat()
            }

            val newArcX = arcStartX * scaleX
            val newArcY = arcStartY * scaleY

            val newPosArc = if (scaleX * scaleY >= 0F) isPositiveArc else !isPositiveArc

            return createNode(
                newHRadius,
                newVRadius,
                newTheta.toFloat(),
                isMoreThanHalf,
                newPosArc,
                newArcX,
                newArcY
            )
        }

        override fun translate(translateX: Float, translateY: Float): PathNode {
            return if (relative) {
                getDefaultNode()
            } else {
                val x = arcStartX + translateX
                val y = arcStartY + translateY

                return createNode(
                    horizontalEllipseRadius,
                    verticalEllipseRadius,
                    theta,
                    isMoreThanHalf,
                    isPositiveArc,
                    x,
                    y
                )
            }
        }

        override fun rotate(rotation: Float, pivotX: Float, pivotY: Float): PathNode {
            val newTheta = (theta + rotation) % 360
            val rad = rotation * PI / 180
            val cos = cos(rad)
            val sin = sin(rad)

            val px = if (relative) 0F else pivotX
            val py = if (relative) 0F else pivotY

            val x = rotateX(arcStartX, arcStartY, px, py, cos, sin)
            val y = rotateY(arcStartX, arcStartY, px, py, cos, sin)

            return createNode(
                horizontalEllipseRadius,
                verticalEllipseRadius,
                newTheta,
                isMoreThanHalf,
                isPositiveArc,
                x,
                y
            )
        }

        private fun getDefaultNode(): PathNode {
            return createNode(
                horizontalEllipseRadius,
                verticalEllipseRadius,
                theta,
                isMoreThanHalf,
                isPositiveArc,
                arcStartX,
                arcStartY
            )
        }

        private fun rotateX(nodeX: Float, nodeY: Float, pivotX: Float, pivotY: Float, cos: Double, sin: Double): Float {
            return (pivotX + (nodeX - pivotX) * cos - (nodeY - pivotY) * sin).toFloat()
        }

        private fun rotateY(nodeX: Float, nodeY: Float, pivotX: Float, pivotY: Float, cos: Double, sin: Double): Float {
            return (pivotY + (nodeX - pivotX) * sin + (nodeY - pivotY) * cos).toFloat()
        }

        abstract fun createNode(
            horizontalEllipseRadius: Float,
            verticalEllipseRadius: Float,
            theta: Float,
            isMoreThanHalf: Boolean,
            isPositiveArc: Boolean,
            arcStartX: Float,
            arcStartY: Float
        ): PathNode
    }

    private class RelativeArcToEditor(node: PathNode.RelativeArcTo) : BaseArcToEditor(
        node.horizontalEllipseRadius,
        node.verticalEllipseRadius,
        node.theta,
        node.isMoreThanHalf,
        node.isPositiveArc,
        node.arcStartDx,
        node.arcStartDy,
        true
    ) {
        override fun createNode(
            horizontalEllipseRadius: Float,
            verticalEllipseRadius: Float,
            theta: Float,
            isMoreThanHalf: Boolean,
            isPositiveArc: Boolean,
            arcStartX: Float,
            arcStartY: Float
        ): PathNode {
            return PathNode.RelativeArcTo(
                horizontalEllipseRadius,
                verticalEllipseRadius,
                theta,
                isMoreThanHalf,
                isPositiveArc,
                arcStartX,
                arcStartY
            )
        }
    }

    private class ArcToEditor(node: PathNode.ArcTo) : BaseArcToEditor(
        node.horizontalEllipseRadius,
        node.verticalEllipseRadius,
        node.theta,
        node.isMoreThanHalf,
        node.isPositiveArc,
        node.arcStartX,
        node.arcStartY,
        false) {
        override fun createNode(
            horizontalEllipseRadius: Float,
            verticalEllipseRadius: Float,
            theta: Float,
            isMoreThanHalf: Boolean,
            isPositiveArc: Boolean,
            arcStartX: Float,
            arcStartY: Float
        ): PathNode {
            return PathNode.ArcTo(
                horizontalEllipseRadius,
                verticalEllipseRadius,
                theta,
                isMoreThanHalf,
                isPositiveArc,
                arcStartX,
                arcStartY
            )
        }
    }
}