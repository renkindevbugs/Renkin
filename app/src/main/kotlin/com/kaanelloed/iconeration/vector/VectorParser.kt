package com.kaanelloed.iconeration.vector

import android.content.res.Resources
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaanelloed.iconeration.vector.brush.ReferenceBrush
import com.kaanelloed.iconeration.xml.XmlNode

class VectorParser(val resources: Resources, private val defaultColor: Color = Color.Unspecified) {
    private val lineCapButt = 0
    private val lineCapRound = 1
    private val lineCapSquare = 2

    private val lineJoinMiter = 0
    private val lineJoinRound = 1
    private val lineJoinBevel = 2

    private fun parse(node: XmlNode): ImageVector? {
        val vectorNode = node.findFirstTag("vector")

        if (vectorNode != null) {
            return parseVector(vectorNode).build()
        }

        return null
    }

    private fun parseVector(node: XmlNode): ImageVector.Builder {
        val name = node.getAttributeValue("name") ?: DefaultGroupName
        val width =
            if (node.containsAttribute("width")) parseDimension(node.getAttributeValue("width")!!) else 0.dp
        val height =
            if (node.containsAttribute("height")) parseDimension(node.getAttributeValue("height")!!) else 0.dp
        val viewportWidth = node.getAttributeValue("viewportWidth")?.toFloat() ?: 0F
        val viewportHeight = node.getAttributeValue("viewportHeight")?.toFloat() ?: 0F
        val tint =
            ColorDecoder.decode(
                resources,
                node.getAttributeValue("tint") ?: ""
            )
        val tintMode = parseTintMode(node.getAttributeValue("tintMode")?.toInt() ?: -1)
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
        val fillType = parseFill(node.getAttributeValue("fillType")?.toInt() ?: 0)
        val name = node.getAttributeValue("name") ?: DefaultPathName
        val fillColor = parseColor(node.getAttributeValue("fillColor"))
        val fillAlpha = node.getAttributeValue("fillAlpha")?.toFloat() ?: 1F
        val strokeColor = parseColor(node.getAttributeValue("strokeColor"))
        val strokeAlpha = node.getAttributeValue("strokeAlpha")?.toFloat() ?: 1F
        val strokeWidth = node.getAttributeValue("strokeWidth")?.toFloat() ?: DefaultStrokeLineWidth
        val strokeLineCap = parseCap(node.getAttributeValue("strokeLineCap")?.toInt() ?: -1)
        val strokeLineJoin = parseJoin(node.getAttributeValue("strokeLineJoin")?.toInt() ?: -1)
        val strokeMiterLimit =
            node.getAttributeValue("strokeMiterLimit")?.toFloat() ?: 1F
        val trimPathStart = node.getAttributeValue("trimPathStart")?.toFloat() ?: DefaultTrimPathStart
        val trimPathEnd = node.getAttributeValue("trimPathEnd")?.toFloat() ?: DefaultTrimPathEnd
        val trimPathOffset =
            node.getAttributeValue("trimPathOffset")?.toFloat() ?: DefaultTrimPathOffset

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

    private fun parseDimension(text: String): Dp {
        return text.filter { it.isDigit() || it == '.' }.toFloat().dp
    }

    private fun parseTintMode(value: Int): BlendMode {
        return when (value) {
            3 -> BlendMode.SrcOver
            5 -> BlendMode.SrcIn
            9 -> BlendMode.SrcAtop
            14 -> BlendMode.Modulate
            15 -> BlendMode.Screen
            16 -> BlendMode.Plus
            else -> BlendMode.SrcIn
        }
    }

    private fun parseCap(cap: Int): StrokeCap {
        return when (cap) {
            lineCapButt -> StrokeCap.Butt
            lineCapRound -> StrokeCap.Round
            lineCapSquare -> StrokeCap.Square
            else -> StrokeCap.Butt
        }
    }

    private fun parseJoin(join: Int): StrokeJoin {
        return when (join) {
            lineJoinMiter -> StrokeJoin.Miter
            lineJoinRound -> StrokeJoin.Round
            lineJoinBevel -> StrokeJoin.Bevel
            else -> StrokeJoin.Bevel
        }
    }

    private fun parseFill(fill: Int): PathFillType {
        return if (fill == 0) PathFillType.NonZero else PathFillType.EvenOdd
    }

    private fun parseColor(color: String?): Brush? {
        if (color.isNullOrBlank())
            return null

        return when (color.first()) {
            '#' -> {
                SolidColor(decodeColor(color))
            }
            '@' -> {
                ReferenceBrush(color, decodeColor(color))
            }
            else -> null
        }
    }

    private fun decodeColor(color: String): Color {
        return ColorDecoder.decode(resources, color, defaultColor)
    }

    companion object {
        fun parse(resources: Resources, node: XmlNode, defaultColor: Color = Color.Unspecified): ImageVector? {
            val vectorParser = VectorParser(resources, defaultColor)
            return vectorParser.parse(node)
        }
    }
}