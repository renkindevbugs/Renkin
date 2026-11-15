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
import dev.renkinProject.renkin.vector.brush.GradientXml
import dev.renkinProject.renkin.vector.brush.ReferenceBrush
import dev.renkinProject.renkin.vector.brush.VectorGradientBrush
import dev.renkinProject.renkin.xml.file.BaseVectorXml
import dev.renkinProject.renkin.xml.file.VectorXml

/**
 * [includeGradients] writes readable gradient children for stored editor vectors; APK export turns it
 * off because a real VectorDrawable cannot hold them (the solid fallback colour stays).
 * [scalingStrokes] marks a stored vector whose strokes scale with its drawn size; never for export.
 */
class VectorExporter(
    val vector: ImageVector,
    private val includeGradients: Boolean = true,
    private val scalingStrokes: Boolean = false
) {
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
        if (scalingStrokes) file.markScalingStrokes()
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

    // ReferenceBrush is intentionally preserved as its resource string so themed vectors keep
    // following @color/icon_color after export; unsupported brush types become transparent.
    private fun setXmlPath(file : BaseVectorXml, path: VectorPath) {
        val gradients = if (!includeGradients) emptyList() else listOfNotNull(
            (path.fill as? VectorGradientBrush)?.let { GradientXml.attributes(GradientXml.TARGET_FILL, it) },
            (path.stroke as? VectorGradientBrush)?.let { GradientXml.attributes(GradientXml.TARGET_STROKE, it) }
        )
        file.path(
            path.pathData.toStringPath(),
            setXmlJoin(path.strokeLineJoin).toString(),
            path.strokeLineWidth,
            setXmlColor(path.fill),
            setXmlColor(path.stroke),
            setXmlFill(path.pathFillType).toString(),
            setXmlCap(path.strokeLineCap).toString(),
            path.fillAlpha,
            path.strokeAlpha,
            gradients,
            GradientXml.TAG
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
            // Never transparent: builds without gradient support paint this colour instead.
            is VectorGradientBrush -> {
                brush.fallbackColor().toHexString()
            }
            else -> {
                "#00000000"
            }
        }
    }

    companion object {
        fun ImageVector.toXml(scalingStrokes: Boolean = false): ByteArray {
            val exporter = VectorExporter(this, scalingStrokes = scalingStrokes)
            return exporter.toXml()
        }

        fun ImageVector.toXmlFile(): VectorXml {
            val exporter = VectorExporter(this)
            return exporter.toXmlFile()
        }

        fun ImageVector.toXmlFile(
            parent: BaseVectorXml,
            includeGradients: Boolean = true,
            scalingStrokes: Boolean = false
        ): BaseVectorXml {
            val exporter = VectorExporter(this, includeGradients, scalingStrokes)
            return exporter.toXmlFile(parent)
        }
    }
}
