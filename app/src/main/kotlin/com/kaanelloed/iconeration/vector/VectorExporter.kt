package com.kaanelloed.iconeration.vector

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import com.kaanelloed.iconeration.vector.PathExporter.Companion.toStringPath
import com.kaanelloed.iconeration.xml.VectorXml

class VectorExporter(val vector: ImageVector) {
    fun toXml(): ByteArray {
        val vectorFile = VectorXml()
        vectorFile.vectorSize(
            vector.defaultWidth.toString(),
            vector.defaultHeight.toString(),
            vector.viewportWidth,
            vector.viewportHeight
        )

        setGroup(vectorFile, vector.root)

        return vectorFile.readAndClose()
    }

    fun toSvg(): String {
        TODO()
    }

    private fun setGroup(file : VectorXml, group: VectorGroup) {
        file.startGroup(group.scaleX, group.scaleY, group.translationX, group.translationY)

        for (child in group) {
            if (child is VectorGroup) {
                setGroup(file, child)
            }

            if (child is VectorPath) {
                setPath(file, child)
            }
        }

        file.endGroup()
    }

    private fun setPath(file : VectorXml, path: VectorPath) {
        file.path(
            path.pathData.toStringPath(),
            path.strokeLineJoin.toString(),
            path.strokeLineWidth,
            "path.fill",
            "path.stroke",
            path.pathFillType.toString(),
            path.strokeLineCap.toString()
        )
    }

    fun ImageVector.toXml(): ByteArray {
        val exporter = VectorExporter(this)
        return exporter.toXml()
    }

    fun ImageVector.toSvg(): String {
        val exporter = VectorExporter(this)
        return exporter.toSvg()
    }
}