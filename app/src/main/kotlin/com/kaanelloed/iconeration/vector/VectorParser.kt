package com.kaanelloed.iconeration.vector

import android.content.res.Resources
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.dp
import com.kaanelloed.iconeration.xml.XmlNode

class VectorParser(val resources: Resources) {
    private fun parse(node: XmlNode): ImageVector? {
        val vectorNode = node.findFirstTag("vector")

        if (vectorNode != null) {
            return parseVector(vectorNode).build()
        }

        return null
    }

    private fun parseVector(node: XmlNode): ImageVector.Builder {
        val name = node.getAttributeValue("name") ?: DefaultGroupName
        val width = 0.dp // node.getAttributeValue("width")?.toFloat()?.dp
        val height = 0.dp // node.getAttributeValue("height")?.toFloat()?.dp
        val viewportWidth = node.getAttributeValue("viewportWidth")?.toFloat() ?: 0F
        val viewportHeight = node.getAttributeValue("viewportHeight")?.toFloat() ?: 0F
        val tint =
            ColorDecoder.decode(
                resources,
                node.getAttributeValue("tint") ?: ""
            )
        val tintMode = BlendMode.SrcIn //TODO() name="tintMode"
        val autoMirrored = node.getAttributeValue("autoMirrored")?.toBoolean() ?: false

        val builder = ImageVector.Builder(
            name,
            width,
            height,
            viewportWidth,
            viewportHeight,
            tint,
            tintMode,
            autoMirrored
        )

        for (child in node.children) {
            if (child.name == "group") {
                parseGroup(builder, child)
            }

            if (child.name == "path") {
                parsePath(builder, child)
            }
        }

        return builder
    }

    private fun parseGroup(builder: ImageVector.Builder, node: XmlNode) {
        val name = node.getAttributeValue("name") ?: DefaultGroupName
        val rotation = node.getAttributeValue("rotation")?.toFloat() ?: DefaultRotation
        val pivotX = node.getAttributeValue("pivotX")?.toFloat() ?: DefaultPivotX
        val pivotY = node.getAttributeValue("pivotY")?.toFloat() ?: DefaultPivotY
        val scaleX = node.getAttributeValue("scaleX")?.toFloat() ?: DefaultScaleX
        val scaleY = node.getAttributeValue("scaleY")?.toFloat() ?: DefaultScaleY
        val translateX = node.getAttributeValue("translateX")?.toFloat() ?: DefaultTranslationX
        val translateY = node.getAttributeValue("translateY")?.toFloat() ?: DefaultTranslationY
        val clipPathData: List<PathNode> = EmptyPath

        builder.addGroup(
            name,
            rotation,
            pivotX,
            pivotY,
            scaleX,
            scaleY,
            translateX,
            translateY,
            clipPathData
        )

        for (child in node.children) {
            if (child.name == "group") {
                parseGroup(builder, child)
            }

            if (child.name == "path") {
                parsePath(builder, child)
            }
        }

        builder.clearGroup()
    }

    private fun parsePath(builder: ImageVector.Builder, node: XmlNode) {
        val rawPathData = node.getAttributeValue("pathData") ?: ""
        val fillType = DefaultFillType
        val name = node.getAttributeValue("name") ?: DefaultPathName
        val fillColor: Brush? = SolidColor(Color.Unspecified) //TODO
        val fillAlpha = node.getAttributeValue("fillAlpha")?.toFloat() ?: 1F
        val strokeColor: Brush? = SolidColor(Color.Unspecified) //TODO
        val strokeAlpha = node.getAttributeValue("strokeAlpha")?.toFloat() ?: 1F
        val strokeWidth = node.getAttributeValue("strokeWidth")?.toFloat() ?: DefaultStrokeLineWidth
        val strokeLineCap = DefaultStrokeLineCap
        val strokeLineJoin = DefaultStrokeLineJoin
        val strokeMiterLimit =
            node.getAttributeValue("strokeMiterLimit")?.toFloat() ?: DefaultStrokeLineMiter
        val trimPathStart = node.getAttributeValue("strokeWidth")?.toFloat() ?: DefaultTrimPathStart
        val trimPathEnd = node.getAttributeValue("strokeWidth")?.toFloat() ?: DefaultTrimPathEnd
        val trimPathOffset =
            node.getAttributeValue("strokeWidth")?.toFloat() ?: DefaultTrimPathOffset

        val pathData = PathParser().parsePathString(rawPathData).toNodes()

        builder.addPath(
            pathData,
            fillType,
            name,
            fillColor,
            fillAlpha,
            strokeColor,
            strokeAlpha,
            strokeWidth,
            strokeLineCap,
            strokeLineJoin,
            strokeMiterLimit,
            trimPathStart,
            trimPathEnd,
            trimPathOffset
        )
    }

    companion object {
        fun parse(resources: Resources, node: XmlNode): ImageVector? {
            val vectorParser = VectorParser(resources)
            return vectorParser.parse(node)
        }
    }
}