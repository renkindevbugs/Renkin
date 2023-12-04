package com.kaanelloed.iconeration.vector

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import com.kaanelloed.iconeration.vector.MutableImageVector.Companion.toMutableImageVector

class VectorEditor(private val vector: ImageVector) {
    private fun scale(factor: Float): ImageVector {
        val mutableVector = vector.toMutableImageVector()
        mutableVector.defaultWidth *= factor
        mutableVector.defaultHeight *= factor

        scaleGroup(mutableVector.root, factor)

        return mutableVector.toImageVector()
    }

    private fun scaleGroup(group: MutableVectorGroup, factor: Float) {
        for (child in group.children) {
            if (child is MutableVectorGroup) {
                scaleGroup(child, factor)
            }

            if (child is MutableVectorPath) {
                scalePath(child, factor)
            }
        }
    }

    private fun scalePath(path: MutableVectorPath, factor: Float) {
        for (i in path.pathData.indices) {
            val node = path.pathData[i]
            path.pathData[i] = scaleNode(node, factor)
        }
    }

    private fun scaleNode(node: PathNode, factor: Float): PathNode {
        return when (node) {
            is PathNode.Close -> node
            is PathNode.RelativeMoveTo -> PathNode.RelativeMoveTo(node.dx * factor, node.dy * factor)
            is PathNode.MoveTo -> PathNode.MoveTo(node.x * factor, node.y * factor)
            is PathNode.RelativeLineTo -> PathNode.RelativeLineTo(node.dx * factor, node.dy * factor)
            is PathNode.LineTo -> PathNode.LineTo(node.x * factor, node.y * factor)
            is PathNode.RelativeHorizontalTo -> PathNode.RelativeHorizontalTo(node.dx * factor)
            is PathNode.HorizontalTo -> PathNode.HorizontalTo(node.x * factor)
            is PathNode.RelativeVerticalTo -> PathNode.RelativeVerticalTo(node.dy * factor)
            is PathNode.VerticalTo -> PathNode.VerticalTo(node.y * factor)
            is PathNode.RelativeCurveTo -> PathNode.RelativeCurveTo(
                node.dx1 * factor,
                node.dy1 * factor,
                node.dx2 * factor,
                node.dy2 * factor,
                node.dx3 * factor,
                node.dy3 * factor
            )
            is PathNode.CurveTo -> PathNode.CurveTo(
                node.x1 * factor,
                node.y1 * factor,
                node.x2 * factor,
                node.y2 * factor,
                node.x3 * factor,
                node.y3 * factor
            )
            is PathNode.RelativeReflectiveCurveTo -> PathNode.RelativeReflectiveCurveTo(
                node.dx1 * factor,
                node.dy1 * factor,
                node.dx2 * factor,
                node.dy2 * factor
            )
            is PathNode.ReflectiveCurveTo -> PathNode.ReflectiveCurveTo(
                node.x1 * factor,
                node.y1 * factor,
                node.x2 * factor,
                node.y2 * factor
            )
            is PathNode.RelativeQuadTo -> PathNode.RelativeQuadTo(
                node.dx1 * factor,
                node.dy1 * factor,
                node.dx2 * factor,
                node.dy2 * factor
            )
            is PathNode.QuadTo -> PathNode.QuadTo(
                node.x1 * factor,
                node.y1 * factor,
                node.x2 * factor,
                node.y2 * factor
            )
            is PathNode.RelativeReflectiveQuadTo -> PathNode.RelativeReflectiveQuadTo(node.dx * factor, node.dy * factor)
            is PathNode.ReflectiveQuadTo -> PathNode.ReflectiveQuadTo(node.x * factor, node.y * factor)
            is PathNode.RelativeArcTo -> PathNode.RelativeArcTo(
                node.horizontalEllipseRadius * factor,
                node.verticalEllipseRadius * factor,
                node.theta,
                node.isMoreThanHalf,
                node.isPositiveArc,
                node.arcStartDx * factor,
                node.arcStartDy * factor
            )
            is PathNode.ArcTo -> PathNode.ArcTo(
                node.horizontalEllipseRadius * factor,
                node.verticalEllipseRadius * factor,
                node.theta,
                node.isMoreThanHalf,
                node.isPositiveArc,
                node.arcStartX * factor,
                node.arcStartY * factor
            )
        }
    }

    companion object {
        fun ImageVector.scale(factor: Float): ImageVector {
            val editor = VectorEditor(this)
            return editor.scale(factor)
        }
    }
}