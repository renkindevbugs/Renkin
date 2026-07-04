package dev.renkinProject.renkin.vector

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import dev.renkinProject.renkin.extension.toHexString
import dev.renkinProject.renkin.vector.PathExporter.Companion.toStringPath
import dev.renkinProject.renkin.vector.brush.ReferenceBrush
import dev.renkinProject.renkin.xml.file.BaseVectorXml
import dev.renkinProject.renkin.xml.file.VectorXml

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

    fun toXmlFile(parent: BaseVectorXml): BaseVectorXml {
        setSize(parent)
        setXmlGroup(parent, vector.root)

        return parent
    }

    fun toXmlFile(): VectorXml {
        val vectorFile = VectorXml()

        setSize(vectorFile)
        setXmlGroup(vectorFile, vector.root)

        return vectorFile
    }

    private fun setSize(file : BaseVectorXml) {
        file.vectorSize(
            vector.defaultWidth.value.toString() + "dp",
            vector.defaultHeight.value.toString() + "dp",
            vector.viewportWidth,
            vector.viewportHeight
        )
    }

    private fun setXmlGroup(file : BaseVectorXml, group: VectorGroup) {
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

    // Known limitation: colours are exported resolved — a path that referenced @color/icon_color
    // loses the reference here, so a re-imported vector can't be re-tinted through the resource.
    // Nothing depends on round-tripping today; revisit only if vector re-import ever needs it.
    private fun setXmlPath(file : BaseVectorXml, path: VectorPath) {
        file.path(
            path.pathData.toStringPath(),
            setXmlJoin(path.strokeLineJoin).toString(),
            path.strokeLineWidth,
            setXmlColor(path.fill),
            setXmlColor(path.stroke),
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

    private fun setXmlColor(brush: Brush?): String {
        return when (brush) {
            null -> {
                "#00000000"
            }
            is SolidColor -> {
                brush.value.toHexString()
            }
            is ReferenceBrush -> {
                return brush.reference
            }
            else -> {
                "#00000000"
            }
        }
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

        fun ImageVector.toXmlFile(parent: BaseVectorXml): BaseVectorXml {
            val exporter = VectorExporter(this)
            return exporter.toXmlFile(parent)
        }
    }
}