package com.kaanelloed.iconeration.vector

import androidx.compose.ui.graphics.vector.PathNode

class PathExporter() {
    private fun export(pathNodes: List<PathNode>): String {
        val builder = StringBuilder()

        for (pathNode in pathNodes) {
            builder.append(nodeToString(pathNode))
        }

        return builder.toString()
    }

    private fun nodeToString(node: PathNode): String {
        return when (node) {
            is PathNode.Close -> "Z"
            is PathNode.RelativeMoveTo -> "m${node.dx},${node.dy}"
            is PathNode.MoveTo -> "M${node.x},${node.y}"
            is PathNode.RelativeLineTo -> "l${node.dx},${node.dy}"
            is PathNode.LineTo -> "L${node.x},${node.y}"
            is PathNode.RelativeHorizontalTo -> "h${node.dx}"
            is PathNode.HorizontalTo -> "H${node.x}"
            is PathNode.RelativeVerticalTo -> "v${node.dy}"
            is PathNode.VerticalTo -> "V${node.y}"
            is PathNode.RelativeCurveTo -> "c${node.dx1},${node.dy1},${node.dx2},${node.dy2},${node.dx3},${node.dy3}"
            is PathNode.CurveTo -> "C${node.x1},${node.y1},${node.x2},${node.y2},${node.x3},${node.y3}"
            is PathNode.RelativeReflectiveCurveTo -> "s${node.dx1},${node.dy1},${node.dx2},${node.dy2}"
            is PathNode.ReflectiveCurveTo -> "S${node.x1},${node.y1},${node.x2},${node.y2}"
            is PathNode.RelativeQuadTo -> "q${node.dx1},${node.dy1},${node.dx2},${node.dy2}"
            is PathNode.QuadTo -> "Q${node.x1},${node.y1},${node.x2},${node.y2}"
            is PathNode.RelativeReflectiveQuadTo -> "t${node.dx},${node.dy}"
            is PathNode.ReflectiveQuadTo -> "T${node.x},${node.y}"
            is PathNode.RelativeArcTo -> "a${node.horizontalEllipseRadius},${node.verticalEllipseRadius} ${node.theta} ${if (node.isMoreThanHalf) 1 else 0} ${if (node.isPositiveArc) 1 else 0} ${node.arcStartDx},${node.arcStartDy}"
            is PathNode.ArcTo -> "A${node.horizontalEllipseRadius},${node.verticalEllipseRadius} ${node.theta} ${if (node.isMoreThanHalf) 1 else 0} ${if (node.isPositiveArc) 1 else 0} ${node.arcStartX},${node.arcStartY}"
        }
    }

    companion object {
        fun List<PathNode>.toStringPath(): String {
            val exporter = PathExporter()
            return exporter.export(this)
        }
    }
}