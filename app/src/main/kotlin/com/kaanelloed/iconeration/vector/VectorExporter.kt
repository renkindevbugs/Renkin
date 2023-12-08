package com.kaanelloed.iconeration.vector

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import com.kaanelloed.iconeration.ui.toHexString
import com.kaanelloed.iconeration.vector.PathExporter.Companion.toStringPath
import com.kaanelloed.iconeration.xml.file.VectorXml

class VectorExporter(val vector: ImageVector) {
    private val lineCapButt = 0
    private val lineCapRound = 1
    private val lineCapSquare = 2

    private val lineJoinMiter = 0
    private val lineJoinRound = 1
    private val lineJoinBevel = 2

    fun toXml(): ByteArray {
        return toXmlFile().readAndClose()
    }

    fun toXmlFile(): VectorXml {
        val vectorFile = VectorXml()
        vectorFile.vectorSize(
            vector.defaultWidth.value.toString() + "dp",
            vector.defaultHeight.value.toString() + "dp",
            vector.viewportWidth,
            vector.viewportHeight
        )

        setXmlGroup(vectorFile, vector.root)

        return vectorFile
    }

    private fun setXmlGroup(file : VectorXml, group: VectorGroup) {
        file.startGroup(
            group.scaleX,
            group.scaleY,
            group.translationX,
            group.translationY,
            group.rotation,
            group.pivotX,
            group.pivotY
        )

        for (child in group) {
            if (child is VectorGroup) {
                setXmlGroup(file, child)
            }

            if (child is VectorPath) {
                setXmlPath(file, child)
            }
        }

        file.endGroup()
    }

    private fun setXmlPath(file : VectorXml, path: VectorPath) {
        file.path(
            path.pathData.toStringPath(),
            setXmlJoin(path.strokeLineJoin).toString(),
            path.strokeLineWidth,
            (path.fill as SolidColor).value.toHexString(),
            "@color/icon_color",
            setXmlFill(path.pathFillType).toString(),
            setXmlCap(path.strokeLineCap).toString(),
            path.fillAlpha,
            path.strokeAlpha
        )
    }

    private fun setXmlCap(cap: StrokeCap): Int {
        return when (cap) {
            StrokeCap.Butt -> lineCapButt
            StrokeCap.Round -> lineCapRound
            StrokeCap.Square -> lineCapSquare
            else -> lineCapButt
        }
    }

    private fun setXmlJoin(join: StrokeJoin): Int {
        return when (join) {
            StrokeJoin.Miter -> lineJoinMiter
            StrokeJoin.Round -> lineJoinRound
            StrokeJoin.Bevel -> lineJoinBevel
            else -> lineJoinBevel
        }
    }

    private fun setXmlFill(fill: PathFillType): Int {
        return if (fill == PathFillType.NonZero) 0 else 1
    }

    companion object {
        fun ImageVector.toXml(): ByteArray {
            val exporter = VectorExporter(this)
            return exporter.toXml()
        }

        fun ImageVector.toXmlFile(): VectorXml {
            val exporter = VectorExporter(this)
            return exporter.toXmlFile()
        }
    }
}