package com.kaanelloed.iconeration.xml

class SvgXml: XmlMemoryFile() {
    private val svgNamespace = "http://www.w3.org/2000/svg"

    init {
        initialize()
    }

    override fun initialize() {
        super.initialize()

        startTag("svg")
        namespace(svgNamespace)
    }

    fun svgSize(width: String, height: String) {
        attribute("width", width)
        attribute("height", height)
    }

    fun startGroup(
        scaleX: Float,
        scaleY: Float,
        translateX: Float,
        translateY: Float,
        rotation: Float,
        pivotX: Float,
        pivotY: Float
    ) {
        val transform = "rotate($rotation) translate($translateX $translateY) scale($scaleX $scaleY)"

        startTag("g")
        attribute("transform", transform)
    }

    fun endGroup() {
        endTag("g")
    }

    fun path(
        pathData: String,
        strokeLineJoin: String,
        strokeWidth: Float,
        fillColor: String,
        strokeColor: String,
        fillType: String,
        strokeLineCap: String,
        fillAlpha: Float,
        strokeAlpha: Float
    ) {
        startTag("path")
        attribute("d", pathData)
        attribute("stroke-linejoin", strokeLineJoin)
        attribute("stroke-width", strokeWidth.toString())
        attribute("fill", fillColor)
        attribute("stroke", strokeColor)
        attribute("stroke-linecap", strokeLineCap)
        attribute("fill-opacity", fillAlpha.toString())
        attribute("stroke-opacity", strokeAlpha.toString())
        endTag("path")
    }

    override fun readAndClose(): ByteArray {
        endTag("svg")
        return super.readAndClose()
    }
}