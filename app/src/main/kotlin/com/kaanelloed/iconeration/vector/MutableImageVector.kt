package com.kaanelloed.iconeration.vector

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.Dp

class MutableImageVector(imageVector: ImageVector) {
    var name: String
    var defaultWidth: Dp
    var defaultHeight: Dp
    var viewportWidth: Float
    var viewportHeight: Float
    var tintColor: Color
    var tintBlendMode: BlendMode
    var autoMirror: Boolean
    var root: MutableVectorGroup

    init {
        name = imageVector.name
        defaultWidth = imageVector.defaultWidth
        defaultHeight = imageVector.defaultHeight
        viewportWidth = imageVector.viewportWidth
        viewportHeight = imageVector.viewportHeight
        tintColor = imageVector.tintColor
        tintBlendMode = imageVector.tintBlendMode
        autoMirror = imageVector.autoMirror
        root = MutableVectorGroup(imageVector.root)
    }

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

    companion object {
        fun ImageVector.toMutableImageVector(): MutableImageVector {
            return MutableImageVector(this)
        }
    }
}

sealed class MutableVectorNode

class MutableVectorGroup(vectorGroup: VectorGroup): MutableVectorNode() {
    var name: String
    var rotation: Float
    var pivotX: Float
    var pivotY: Float
    var scaleX: Float
    var scaleY: Float
    var translationX: Float
    var translationY: Float
    val clipPathData: MutableList<PathNode>
    val children: MutableList<MutableVectorNode>

    init {
        name = vectorGroup.name
        rotation = vectorGroup.rotation
        pivotX = vectorGroup.pivotX
        pivotY = vectorGroup.pivotY
        scaleX = vectorGroup.scaleX
        scaleY = vectorGroup.scaleY
        translationX = vectorGroup.translationX
        translationY = vectorGroup.translationY
        clipPathData = vectorGroup.clipPathData.toMutableList()
        children = mutableListOf()

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
    var name: String
    val pathData: MutableList<PathNode>
    var pathFillType: PathFillType
    var fill: Brush?
    var fillAlpha: Float
    var stroke: Brush?
    var strokeAlpha: Float
    var strokeLineWidth: Float
    var strokeLineCap: StrokeCap
    var strokeLineJoin: StrokeJoin
    var strokeLineMiter: Float
    var trimPathStart: Float
    var trimPathEnd: Float
    var trimPathOffset: Float

    init {
        name = vectorPath.name
        pathData = vectorPath.pathData.toMutableList()
        pathFillType = vectorPath.pathFillType
        fill = vectorPath.fill
        fillAlpha = vectorPath.fillAlpha
        stroke = vectorPath.stroke
        strokeAlpha = vectorPath.strokeAlpha
        strokeLineWidth = vectorPath.strokeLineWidth
        strokeLineCap = vectorPath.strokeLineCap
        strokeLineJoin = vectorPath.strokeLineJoin
        strokeLineMiter = vectorPath.strokeLineMiter
        trimPathStart = vectorPath.trimPathStart
        trimPathEnd = vectorPath.trimPathEnd
        trimPathOffset = vectorPath.trimPathOffset
    }
}