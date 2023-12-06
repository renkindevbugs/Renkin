package com.kaanelloed.iconeration.vector

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import com.kaanelloed.iconeration.vector.MutableImageVector.Companion.toMutableImageVector

class VectorEditor internal constructor(private val mutableVector: MutableImageVector) {
    private fun scale(factor: Float): MutableImageVector {
        var move = factor * mutableVector.viewportWidth / 2
        if (factor > 1) {
            move *= -1
        }

        scaleGroup(mutableVector.root, factor, move)

        return mutableVector
    }

    private fun scaleGroup(group: MutableVectorGroup, factor: Float, move: Float) {
        for (child in group.children) {
            if (child is MutableVectorGroup) {
                scaleGroup(child, factor, move)
            }

            if (child is MutableVectorPath) {
                scalePath(child, factor, move)
            }
        }
    }

    private fun scalePath(path: MutableVectorPath, factor: Float, move: Float) {
        for (i in path.pathData.indices) {
            val node = path.pathData[i]
            path.pathData[i] = scaleNode(node, factor, move)
        }
    }

    private fun scaleNode(node: PathNode, factor: Float, move: Float): PathNode {
        return when (node) {
            is PathNode.Close -> node
            is PathNode.RelativeMoveTo -> PathNode.RelativeMoveTo(node.dx * factor + move, node.dy * factor + move)
            is PathNode.MoveTo -> PathNode.MoveTo(node.x * factor + move, node.y * factor + move)
            is PathNode.RelativeLineTo -> PathNode.RelativeLineTo(node.dx * factor + move, node.dy * factor + move)
            is PathNode.LineTo -> PathNode.LineTo(node.x * factor + move, node.y * factor + move)
            is PathNode.RelativeHorizontalTo -> PathNode.RelativeHorizontalTo(node.dx * factor + move)
            is PathNode.HorizontalTo -> PathNode.HorizontalTo(node.x * factor + move)
            is PathNode.RelativeVerticalTo -> PathNode.RelativeVerticalTo(node.dy * factor + move)
            is PathNode.VerticalTo -> PathNode.VerticalTo(node.y * factor + move)
            is PathNode.RelativeCurveTo -> PathNode.RelativeCurveTo(
                node.dx1 * factor + move,
                node.dy1 * factor + move,
                node.dx2 * factor + move,
                node.dy2 * factor + move,
                node.dx3 * factor + move,
                node.dy3 * factor + move
            )
            is PathNode.CurveTo -> PathNode.CurveTo(
                node.x1 * factor + move,
                node.y1 * factor + move,
                node.x2 * factor + move,
                node.y2 * factor + move,
                node.x3 * factor + move,
                node.y3 * factor + move
            )
            is PathNode.RelativeReflectiveCurveTo -> PathNode.RelativeReflectiveCurveTo(
                node.dx1 * factor + move,
                node.dy1 * factor + move,
                node.dx2 * factor + move,
                node.dy2 * factor + move
            )
            is PathNode.ReflectiveCurveTo -> PathNode.ReflectiveCurveTo(
                node.x1 * factor + move,
                node.y1 * factor + move,
                node.x2 * factor + move,
                node.y2 * factor + move
            )
            is PathNode.RelativeQuadTo -> PathNode.RelativeQuadTo(
                node.dx1 * factor + move,
                node.dy1 * factor + move,
                node.dx2 * factor + move,
                node.dy2 * factor + move
            )
            is PathNode.QuadTo -> PathNode.QuadTo(
                node.x1 * factor + move,
                node.y1 * factor + move,
                node.x2 * factor + move,
                node.y2 * factor + move
            )
            is PathNode.RelativeReflectiveQuadTo -> PathNode.RelativeReflectiveQuadTo(node.dx * factor + move, node.dy * factor + move)
            is PathNode.ReflectiveQuadTo -> PathNode.ReflectiveQuadTo(node.x * factor + move, node.y * factor + move)
            is PathNode.RelativeArcTo -> PathNode.RelativeArcTo(
                node.horizontalEllipseRadius * factor,
                node.verticalEllipseRadius * factor,
                node.theta,
                node.isMoreThanHalf,
                node.isPositiveArc,
                node.arcStartDx * factor + move,
                node.arcStartDy * factor + move
            )
            is PathNode.ArcTo -> PathNode.ArcTo(
                node.horizontalEllipseRadius * factor,
                node.verticalEllipseRadius * factor,
                node.theta,
                node.isMoreThanHalf,
                node.isPositiveArc,
                node.arcStartX * factor + move,
                node.arcStartY * factor + move
            )
        }
    }

    companion object {
        fun ImageVector.scale(factor: Float): ImageVector {
            val mutableVector = this.toMutableImageVector()
            return mutableVector.scale(factor).toImageVector()
        }

        fun MutableImageVector.scale(factor: Float): MutableImageVector {
            val editor = VectorEditor(this)
            return editor.scale(factor)
        }
    }
}