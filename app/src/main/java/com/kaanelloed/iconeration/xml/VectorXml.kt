package com.kaanelloed.iconeration.xml

class VectorXml: XmlMemoryFile() {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    init {
        initialize()
    }

    override fun initialize() {
        super.initialize()
        startTag("vector")
    }

    fun vectorSize(width: String, height: String, viewportWidth: Float, viewportHeight: Float) {
        attribute("width", width, androidNamespace)
        attribute("height", height, androidNamespace)
        attribute("viewportWidth", viewportWidth.toString(), androidNamespace)
        attribute("viewportHeight", viewportHeight.toString(), androidNamespace)
    }

    fun startGroup(scaleX: Float, scaleY: Float, translateX: Float, translateY: Float) {
        startTag("group")
        attribute("scaleX", scaleX.toString(), androidNamespace)
        attribute("scaleY", scaleY.toString(), androidNamespace)
        attribute("translateX", translateX.toString(), androidNamespace)
        attribute("translateY", translateY.toString(), androidNamespace)
    }

    fun endGroup() {
        endTag("group")
    }

    fun path(pathData: String, strokeLineJoin: String, strokeWidth: Float, fillColor: String, strokeColor: String, fillType: String, strokeLineCap: String) {
        startTag("path")
        attribute("pathData", pathData, androidNamespace)
        attribute("strokeLineJoin", strokeLineJoin, androidNamespace)
        attribute("strokeWidth", strokeWidth.toString(), androidNamespace)
        attribute("fillColor", fillColor, androidNamespace)
        attribute("strokeColor", strokeColor, androidNamespace)
        attribute("fillType", fillType, androidNamespace)
        attribute("strokeLineCap", strokeLineCap, androidNamespace)
        endTag("path")
    }

    override fun readAndClose(): ByteArray {
        endTag("vector")
        return super.readAndClose()
    }
}