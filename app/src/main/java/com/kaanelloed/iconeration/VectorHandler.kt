package com.kaanelloed.iconeration

import android.graphics.Color
import com.kaanelloed.iconeration.xml.VectorXml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.StringReader

class VectorHandler {
    lateinit var vector: Vector

    fun parse(parser: XmlPullParser) {
        var currentGroup: Group? = null
        val elements = ArrayDeque<String>()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                elements.addLast(parser.name)

                if (parser.name == "vector") {
                    vector = Vector.parse(parser)
                }
                if (parser.name == "group") {
                    val group = Group.parse(parser)
                    vector.groups.add(group)
                    currentGroup = group
                }
                if (parser.name == "path") {
                    val path = Path.parse(parser)
                    if (currentGroup != null)
                        currentGroup.paths.add(path)
                    else
                        vector.paths.add(path)
                }
                if (parser.name == "clip-path") {
                    val clip = ClipPath.parse(parser)
                }
            }

            if (parser.eventType == XmlPullParser.END_TAG) {
                if (elements.removeLast() == "group") {
                    currentGroup = null
                }
            }

            parser.next();
        }
    }

    fun parseSvg(svg: String) {
        val parser = getPullParser()
        parser.setInput(StringReader(svg))

        parseSvg(parser)
    }

    fun parseSvg(parser: XmlPullParser) {
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                if (parser.name == "svg") {
                    vector = Vector.parse(parser)
                }

                if (parser.name == "path") {
                    val path = Path.parseSvg(parser)
                    vector.paths.add(path)
                }
            }

            parser.next();
        }
    }

    fun toSVG(): String {
        var svg = "<svg viewBox=\"0 0 ${vector.viewportWidth} ${vector.viewportHeight}\">"
        for (grp in vector.groups) {
            svg += "<g transform=\"translate(${grp.translateX} ${grp.translateY}) scale(${grp.scaleX} ${grp.scaleY})\">"
            for (path in grp.paths) {
                svg += "<path fill=\"${path.fillColor}\" stroke=\"${path.strokeColor}\" stroke-width=\"${path.strokeWidth}\" d=\"${path.pathDataRaw}\" />"
            }
            svg += "</g>"
        }
        for (path in vector.paths) {
            svg += "<path fill=\"${path.fillColor}\" stroke=\"${path.strokeColor}\" stroke-width=\"${path.strokeWidth}\" d=\"${path.pathDataRaw}\" />"
        }

        svg += "</svg>"

        return svg
    }

    fun toSVGParser(): XmlPullParser {
        val svg = toSVG()
        val parser = getPullParser()
        parser.setInput(StringReader(svg))

        return parser
    }

    fun toXMLFile(): VectorXml {
        val xml = VectorXml()

        xml.vectorSize(vector.width, vector.height, vector.viewportWidth, vector.viewportHeight)

        for (grp in vector.groups) {
            xml.startGroup(grp.scaleX, grp.scaleY, grp.translateX, grp.translateY)
            for (path in grp.paths) {
                xml.path(path.pathDataRaw, path.strokeLineJoin, path.strokeWidth, path.fillColor.toString(), "@color/icon_color", path.fillType, path.strokeLineCap)
            }
            xml.endGroup()
        }

        for (path in vector.paths) {
            xml.path(path.pathDataRaw, path.strokeLineJoin, path.strokeWidth, path.fillColor.toString(), "@color/icon_color", path.fillType, path.strokeLineCap)
        }

        return xml
    }

    fun toXML(): ByteArray {
        return toXMLFile().readAndClose()
    }

    fun toXMLParser(): XmlPullParser {
        val parser = getPullParser()
        parser.setInput(ByteArrayInputStream(toXML()), "UTF-8")

        return parser
    }

    private fun getPullParser(): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newPullParser()
    }

    class Vector(var name: String
        , var width: String //Dimension
        , var height: String
        , var viewportWidth: Float
        , var viewportHeight: Float
        , var tint: Color?
        , var tintMode: String
        , var autoMirrored: Boolean
        , var alpha: Float) {
        val groups = mutableListOf<Group>()
        val paths = mutableListOf<Path>()

        companion object Factory {
            fun parse(parser: XmlPullParser): Vector {
                var name = ""
                var width = ""
                var height = ""
                var viewportWidth = 0F
                var viewportHeight = 0F
                var tint: Color? = null
                var tintMode = "src_in"
                var autoMirrored = false
                var alpha = 1F

                for (i in 0 until parser.attributeCount) {
                    val namespace = parser.getAttributeNamespace(i)
                    val attrName = parser.getAttributeName(i)
                    val attrValue = parser.getAttributeValue(i)

                    when (attrName) {
                        "name" -> name = attrValue
                        "width" -> width = attrValue
                        "height" -> height = attrValue
                        "viewportWidth" -> viewportWidth = attrValue.toFloat()
                        "viewportHeight" -> viewportHeight = attrValue.toFloat()
                        "tint" -> tint = null
                        "tintMode" -> tintMode = attrValue
                        "autoMirrored" -> autoMirrored = attrValue.toBoolean()
                        "alpha" -> alpha = attrValue.toFloat()
                    }
                }

                return Vector(name, width, height, viewportWidth, viewportHeight, tint, tintMode, autoMirrored, alpha)
            }
        }
    }

    class Group(var name: String
        , var rotation: Float
        , var pivotX: Float
        , var pivotY: Float
        , var scaleX: Float
        , var scaleY: Float
        , var translateX: Float
        , var translateY: Float) {
        val paths = mutableListOf<Path>()

        companion object Factory {
            fun parse(parser: XmlPullParser): Group {
                var name = ""
                var rotation = 0F
                var pivotX = 0F
                var pivotY = 0F
                var scaleX = 1F
                var scaleY = 1F
                var translateX = 0F
                var translateY = 0F

                for (i in 0 until parser.attributeCount) {
                    val namespace = parser.getAttributeNamespace(i)
                    val attrName = parser.getAttributeName(i)
                    val attrValue = parser.getAttributeValue(i)

                    when (attrName) {
                        "name" -> name = attrValue
                        "rotation" -> rotation = attrValue.toFloat()
                        "pivotX" -> pivotX = attrValue.toFloat()
                        "pivotY" -> pivotY = attrValue.toFloat()
                        "scaleX" -> scaleX = attrValue.toFloat()
                        "scaleY" -> scaleY = attrValue.toFloat()
                        "translateX" -> translateX = attrValue.toFloat()
                        "translateY" -> translateY = attrValue.toFloat()
                    }
                }

                return Group(name, rotation, pivotX, pivotY, scaleX, scaleY, translateX, translateY)
            }
        }
    }

    class Path(var name: String
        , var pathDataRaw: String
        , var fillColor: ColorResource
        , var strokeColor: ColorResource
        , var strokeWidth: Float
        , var strokeAlpha: Float
        , var fillAlpha: Float
        , var trimPathStart: Float
        , var trimPathEnd: Float
        , var trimPathOffset: Float
        , var strokeLineCap: String
        , var strokeLineJoin: String //bevel, miter, round
        , var strokeMiterLimit: Float
        , var fillType: String //evenOdd, nonZero
        ) { //butt, round, square
        companion object Factory {
            fun parse(parser: XmlPullParser): Path {
                var name = ""
                var pathData = ""
                var fillColor = ColorResource()
                var strokeColor = ColorResource()
                var strokeWidth = 0F
                var strokeAlpha = 1F
                var fillAlpha = 1F
                var trimPathStart = 0F
                var trimPathEnd = 1F
                var trimPathOffset = 0F
                var strokeLineCap = "butt"
                var strokeLineJoin = "miter"
                var strokeMiterLimit = 4F
                var fillType = "nonZero"


                for (i in 0 until parser.attributeCount) {
                    val namespace = parser.getAttributeNamespace(i)
                    val attrName = parser.getAttributeName(i)
                    val attrValue = parser.getAttributeValue(i)

                    when (attrName) {
                        "name" -> name = attrValue
                        "pathData" -> pathData = attrValue
                        "fillColor" -> fillColor.parse(attrValue)
                        "strokeColor" -> strokeColor.parse(attrValue)
                        "strokeWidth" -> strokeWidth = attrValue.toFloat()
                        "fillAlpha" -> fillAlpha = attrValue.toFloat()
                        "strokeAlpha" -> strokeAlpha = attrValue.toFloat()
                        "trimPathStart" -> trimPathStart = attrValue.toFloat()
                        "trimPathEnd" -> trimPathEnd = attrValue.toFloat()
                        "trimPathOffset" -> trimPathOffset = attrValue.toFloat()
                        "strokeLineCap" -> strokeLineCap = attrValue
                        "strokeLineJoin" -> strokeLineJoin = attrValue
                        "strokeMiterLimit" -> strokeMiterLimit = attrValue.toFloat()
                        "fillType" -> fillType = attrValue
                    }
                }

                return Path(name, pathData, fillColor, strokeColor, strokeWidth, strokeAlpha, fillAlpha, trimPathStart, trimPathEnd, trimPathOffset, strokeLineCap, strokeLineJoin, strokeMiterLimit, fillType)
            }

            fun parseSvg(parser: XmlPullParser): Path {
                var name = ""
                var pathData = ""
                var fillColor = ColorResource()
                var strokeColor = ColorResource()
                var strokeWidth = 0F
                var strokeAlpha = 1F
                var fillAlpha = 1F
                var trimPathStart = 0F
                var trimPathEnd = 1F
                var trimPathOffset = 0F
                var strokeLineCap = "butt"
                var strokeLineJoin = "miter"
                var strokeMiterLimit = 4F
                var fillType = "nonZero"


                for (i in 0 until parser.attributeCount) {
                    val namespace = parser.getAttributeNamespace(i)
                    val attrName = parser.getAttributeName(i)
                    val attrValue = parser.getAttributeValue(i)

                    when (attrName) {
                        "d" -> pathData = attrValue
                        "fill" -> fillColor.parse(attrValue)
                        "stroke" -> strokeColor.parse(attrValue)
                        "stroke-width" -> strokeWidth = attrValue.toFloat()
                        "opacity" -> fillAlpha = attrValue.toFloat()
                    }
                }

                return Path(name, pathData, fillColor, strokeColor, strokeWidth, strokeAlpha, fillAlpha, trimPathStart, trimPathEnd, trimPathOffset, strokeLineCap, strokeLineJoin, strokeMiterLimit, fillType)
            }

            private fun getColor(value: String): Color {
                if (value == "none")
                    return Color.valueOf(Color.TRANSPARENT)

                if (value.startsWith("rgb")) {
                    val red = value.substring(4, 7).toFloat()
                    val green = value.substring(8, 11).toFloat()
                    val blue = value.substring(12, 15).toFloat()

                    return Color.valueOf(red, green, blue)
                }

                if (value.startsWith("@")) {
                    //TODO: Handle color resource id and gradient

                    return Color.valueOf(Color.BLACK)
                }

                var newValue = value

                if (newValue.length == 2) {
                    newValue = "#" + newValue[1].toString().repeat(8)
                }

                if (newValue.length == 4) {
                    newValue = "#FF" + newValue[1] + newValue[1] + newValue[2] + newValue[2] + newValue[3] + newValue[3]
                }

                if (newValue.length == 5) {
                    newValue = "#" + newValue[1] + newValue[1] + newValue[2] + newValue[2] + newValue[3] + newValue[3] + newValue[4] + newValue[4]
                }

                if (newValue.length < 7) {
                    newValue += "0".repeat(7 - newValue.length)
                }

                return Color.valueOf(Color.parseColor(newValue))
            }
        }
    }

    private class ClipPath(var name: String
                           , var pathDataRaw: String) {
        companion object Factory {
            fun parse(parser: XmlPullParser): ClipPath {
                var name = ""
                var pathData = ""
                for (i in 0 until parser.attributeCount) {
                    val namespace = parser.getAttributeNamespace(i)
                    val attrName = parser.getAttributeName(i)
                    val attrValue = parser.getAttributeValue(i)

                    when (attrName) {
                        "name" -> name = attrValue
                        "pathData" -> pathData = attrValue
                    }
                }

                return ClipPath(name, pathData)
            }
        }
    }

    private class PathData(private val type: Char) {
        companion object Factory {
            fun parse(data: String): Array<PathData> {
                val paths = mutableListOf<PathData>()
                var currComp = 0
                var i = 0

                for (c in data) {
                    if (c.isLetter() && i > 0) {
                        val path = PathData(c)
                        path.parse(data.substring(currComp, i))
                        paths.add(path)

                        currComp = i
                    }

                    i++
                }

                val path = PathData(data[currComp])
                path.parse(data.substring(currComp))
                paths.add(path)

                return paths.toTypedArray()
            }
        }

        private fun parse(data: String) {
            val components = data.split(' ')

            for (component in components) {

            }
        }

        private fun getDataType(letter: Char): PathDataType {
            return when (letter) {
                'M' -> PathDataType.AbsoluteMoveTo
                'm' -> PathDataType.RelativeMoveTo
                'L' -> PathDataType.AbsoluteLineTo
                'l' -> PathDataType.RelativeLineTo
                'H' -> PathDataType.AbsoluteHorizontalLineTo
                'h' -> PathDataType.RelativeHorizontalLineTo
                'V' -> PathDataType.AbsoluteVerticalLineTo
                'v' -> PathDataType.RelativeVerticalLineTo
                'C' -> PathDataType.AbsoluteCubicBezierCurve
                'c' -> PathDataType.RelativeCubicBezierCurve
                'S' -> PathDataType.AbsoluteSmoothCubicBezierCurve
                's' -> PathDataType.RelativeSmoothCubicBezierCurve
                'Q' -> PathDataType.AbsoluteQuadraticBezierCurve
                'q' -> PathDataType.RelativeQuadraticBezierCurve
                'T' -> PathDataType.AbsoluteSmoothQuadraticBezierCurve
                't' -> PathDataType.RelativeSmoothQuadraticBezierCurve
                'A' -> PathDataType.AbsoluteEllipticalArcCurve
                'a' -> PathDataType.RelativeEllipticalArcCurve
                'Z' -> PathDataType.AbsoluteClosePath
                'z' -> PathDataType.RelativeClosePath
                else -> PathDataType.AbsoluteMoveTo
            }
        }
    }

    private abstract class PathComponent {
        abstract fun getComponentCount(): Int
        abstract override fun toString(): String
    }

    private class MoveToComponent(private val x: Float, private val y: Float): PathComponent() {
        override fun getComponentCount() = 2
        override fun toString(): String {
            return "$x,$y"
        }
    }

    private class LineToComponent(private val x: Float, private val y: Float): PathComponent() {
        override fun getComponentCount() = 2
        override fun toString(): String {
            return "$x,$y"
        }
    }

    private class HorizontalLineToComponent(private val x: Float): PathComponent() {
        override fun getComponentCount() = 1
        override fun toString(): String {
            return x.toString()
        }
    }

    private class VerticalLineToComponent(private val y: Float): PathComponent() {
        override fun getComponentCount() = 1
        override fun toString(): String {
            return y.toString()
        }
    }

    private class CubicBezierCurveComponent(
        private val x1: Float, private val y1: Float,
        private val x2: Float, private val y2: Float,
        private val x: Float, private val y: Float): PathComponent() {
        override fun getComponentCount() = 6
        override fun toString(): String {
            return "$x1,$y1 $x2,$y2 $x,$y"
        }
    }

    private class SmoothCubicBezierCurveComponent(
        private val x2: Float, private val y2: Float,
        private val x: Float, private val y: Float): PathComponent() {
        override fun getComponentCount() = 4
        override fun toString(): String {
            return "$x2,$y2 $x,$y"
        }
    }

    private class QuadraticBezierCurveComponent(
        private val x1: Float, private val y1: Float,
        private val x: Float, private val y: Float): PathComponent() {
        override fun getComponentCount() = 4
        override fun toString(): String {
            return "$x1,$y1 $x,$y"
        }
    }

    private class SmoothQuadraticBezierCurveComponent(private val x: Float, private val y: Float): PathComponent() {
        override fun getComponentCount() = 2
        override fun toString(): String {
            return "$x,$y"
        }
    }

    private class EllipticalArcCurveComponent(
        private val rx: Float, private val ry: Float, private val angle: Float,
        private val largeArc: Boolean, private val sweep: Boolean,
        private val x: Float, private val y: Float): PathComponent() {
        override fun getComponentCount() = 7
        override fun toString(): String {
            return "$rx $ry $angle $largeArc $sweep $x,$y"
        }
    }

    private class ClosePathComponent(): PathComponent() {
        override fun getComponentCount() = 0
        override fun toString(): String {
            return ""
        }
    }

    enum class PathDataType(val char: Char, val parent: PathDataParentType) {
        AbsoluteMoveTo('M', PathDataParentType.MoveTo),
        RelativeMoveTo('m', PathDataParentType.MoveTo),
        AbsoluteLineTo('L', PathDataParentType.LineTo),
        RelativeLineTo('l', PathDataParentType.LineTo),
        AbsoluteHorizontalLineTo('H', PathDataParentType.HorizontalLineTo),
        RelativeHorizontalLineTo('h', PathDataParentType.HorizontalLineTo),
        AbsoluteVerticalLineTo('V', PathDataParentType.VerticalLineTo),
        RelativeVerticalLineTo('v', PathDataParentType.VerticalLineTo),
        AbsoluteCubicBezierCurve('C', PathDataParentType.CubicBezierCurve),
        RelativeCubicBezierCurve('c', PathDataParentType.CubicBezierCurve),
        AbsoluteSmoothCubicBezierCurve('S', PathDataParentType.SmoothCubicBezierCurve),
        RelativeSmoothCubicBezierCurve('s', PathDataParentType.SmoothCubicBezierCurve),
        AbsoluteQuadraticBezierCurve('Q', PathDataParentType.QuadraticBezierCurve),
        RelativeQuadraticBezierCurve('q', PathDataParentType.QuadraticBezierCurve),
        AbsoluteSmoothQuadraticBezierCurve('T', PathDataParentType.SmoothQuadraticBezierCurve),
        RelativeSmoothQuadraticBezierCurve('t', PathDataParentType.SmoothQuadraticBezierCurve),
        AbsoluteEllipticalArcCurve('A', PathDataParentType.EllipticalArcCurve),
        RelativeEllipticalArcCurve('a', PathDataParentType.EllipticalArcCurve),
        AbsoluteClosePath('Z', PathDataParentType.ClosePath),
        RelativeClosePath('z', PathDataParentType.ClosePath),
    }

    enum class PathDataParentType {
        MoveTo,
        LineTo,
        HorizontalLineTo,
        VerticalLineTo,
        CubicBezierCurve,
        SmoothCubicBezierCurve,
        QuadraticBezierCurve,
        SmoothQuadraticBezierCurve,
        EllipticalArcCurve,
        ClosePath,
    }
}