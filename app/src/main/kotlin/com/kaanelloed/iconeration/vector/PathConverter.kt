package com.kaanelloed.iconeration.vector

import android.graphics.PathIterator
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.PathNode

class PathConverter {
    companion object {
        fun PathNode.toRelative(): PathNode {
            return when (this) {
                is PathNode.ArcTo -> PathNode.RelativeArcTo(
                    this.horizontalEllipseRadius,
                    this.verticalEllipseRadius,
                    this.theta,
                    this.isMoreThanHalf,
                    this.isPositiveArc,
                    this.arcStartX,
                    this.arcStartY
                )
                is PathNode.Close -> this
                is PathNode.CurveTo -> PathNode.RelativeCurveTo(this.x1, this.y1, this.x2, this.y2, this.x3, this.y3)
                is PathNode.HorizontalTo -> PathNode.RelativeHorizontalTo(this.x)
                is PathNode.LineTo -> PathNode.RelativeLineTo(this.x, this.y)
                is PathNode.MoveTo -> PathNode.RelativeMoveTo(this.x, this.y)
                is PathNode.QuadTo -> PathNode.RelativeQuadTo(this.x1, this.y1, this.x2, this.y2)
                is PathNode.ReflectiveCurveTo -> PathNode.RelativeReflectiveCurveTo(this.x1, this.y1, this.x2, this.y2)
                is PathNode.ReflectiveQuadTo -> PathNode.RelativeReflectiveQuadTo(this.x, this.y)
                is PathNode.RelativeArcTo -> this
                is PathNode.RelativeCurveTo -> this
                is PathNode.RelativeHorizontalTo -> this
                is PathNode.RelativeLineTo -> this
                is PathNode.RelativeMoveTo -> this
                is PathNode.RelativeQuadTo -> this
                is PathNode.RelativeReflectiveCurveTo -> this
                is PathNode.RelativeReflectiveQuadTo -> this
                is PathNode.RelativeVerticalTo -> this
                is PathNode.VerticalTo -> PathNode.RelativeVerticalTo(this.y)
            }
        }

        fun PathNode.toAbsolute(): PathNode {
            return when (this) {
                is PathNode.ArcTo -> this
                is PathNode.Close -> this
                is PathNode.CurveTo -> this
                is PathNode.HorizontalTo -> this
                is PathNode.LineTo -> this
                is PathNode.MoveTo -> this
                is PathNode.QuadTo -> this
                is PathNode.ReflectiveCurveTo -> this
                is PathNode.ReflectiveQuadTo -> this
                is PathNode.RelativeArcTo -> PathNode.ArcTo(
                    this.horizontalEllipseRadius,
                    this.verticalEllipseRadius,
                    this.theta,
                    this.isMoreThanHalf,
                    this.isPositiveArc,
                    this.arcStartDx,
                    this.arcStartDy
                )
                is PathNode.RelativeCurveTo -> PathNode.CurveTo(this.dx1, this.dy1, this.dx2, this.dy2, this.dx3, this.dy3)
                is PathNode.RelativeHorizontalTo -> PathNode.HorizontalTo(this.dx)
                is PathNode.RelativeLineTo -> PathNode.LineTo(this.dx, this.dy)
                is PathNode.RelativeMoveTo -> PathNode.MoveTo(this.dx, this.dy)
                is PathNode.RelativeQuadTo -> PathNode.QuadTo(this.dx1, this.dy1, this.dx2, this.dy2)
                is PathNode.RelativeReflectiveCurveTo -> PathNode.ReflectiveCurveTo(this.dx1, this.dy1, this.dx2, this.dy2)
                is PathNode.RelativeReflectiveQuadTo -> PathNode.ReflectiveQuadTo(this.dx, this.dy)
                is PathNode.RelativeVerticalTo -> PathNode.VerticalTo(this.dy)
                is PathNode.VerticalTo -> this
            }
        }

        fun PathNode.isRelative(): Boolean {
            return when (this) {
                is PathNode.ArcTo -> false
                is PathNode.Close -> true
                is PathNode.CurveTo -> false
                is PathNode.HorizontalTo -> false
                is PathNode.LineTo -> false
                is PathNode.MoveTo -> false
                is PathNode.QuadTo -> false
                is PathNode.ReflectiveCurveTo -> false
                is PathNode.ReflectiveQuadTo -> false
                is PathNode.RelativeArcTo -> true
                is PathNode.RelativeCurveTo -> true
                is PathNode.RelativeHorizontalTo -> true
                is PathNode.RelativeLineTo -> true
                is PathNode.RelativeMoveTo -> true
                is PathNode.RelativeQuadTo -> true
                is PathNode.RelativeReflectiveCurveTo -> true
                is PathNode.RelativeReflectiveQuadTo -> true
                is PathNode.RelativeVerticalTo -> true
                is PathNode.VerticalTo -> false
            }
        }

        fun PathNode.isAbsolute(): Boolean {
            return when (this) {
                is PathNode.ArcTo -> true
                is PathNode.Close -> true
                is PathNode.CurveTo -> true
                is PathNode.HorizontalTo -> true
                is PathNode.LineTo -> true
                is PathNode.MoveTo -> true
                is PathNode.QuadTo -> true
                is PathNode.ReflectiveCurveTo -> true
                is PathNode.ReflectiveQuadTo -> true
                is PathNode.RelativeArcTo -> false
                is PathNode.RelativeCurveTo -> false
                is PathNode.RelativeHorizontalTo -> false
                is PathNode.RelativeLineTo -> false
                is PathNode.RelativeMoveTo -> false
                is PathNode.RelativeQuadTo -> false
                is PathNode.RelativeReflectiveCurveTo -> false
                is PathNode.RelativeReflectiveQuadTo -> false
                is PathNode.RelativeVerticalTo -> false
                is PathNode.VerticalTo -> true
            }
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        fun Path.toNodes(): List<PathNode> {
            val nodes = mutableListOf<PathNode>()
            val androidPath = this.asAndroidPath()

            val iterator = androidPath.pathIterator

            while (iterator.hasNext()) {
                val segment = iterator.next()
                val points = segment.points

                when (segment.verb) {
                    PathIterator.VERB_MOVE -> {
                        nodes.add(PathNode.MoveTo(points[0], points[1]))
                    }

                    PathIterator.VERB_CLOSE -> {
                        nodes.add(PathNode.Close)
                    }

                    PathIterator.VERB_CONIC -> {
                        val weight = segment.conicWeight
                        TODO()
                    }

                    PathIterator.VERB_CUBIC -> {
                        nodes.add(PathNode.CurveTo(points[2], points[3], points[4], points[5], points[6], points[7]))
                    }

                    PathIterator.VERB_DONE -> {
                        nodes.add(PathNode.Close)
                    }

                    PathIterator.VERB_LINE -> {
                        nodes.add(PathNode.LineTo(points[2], points[3]))
                    }

                    PathIterator.VERB_QUAD -> {
                        nodes.add(PathNode.QuadTo(points[2], points[3], points[4], points[5]))
                    }
                }
            }

            return nodes.toList()
        }
    }
}