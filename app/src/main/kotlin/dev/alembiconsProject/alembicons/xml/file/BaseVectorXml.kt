package dev.alembiconsProject.alembicons.xml.file

abstract class BaseVectorXml: XmlMemoryFile() {
    protected val androidNamespace = "http://schemas.android.com/apk/res/android"

    fun vectorSize(width: String, height: String, viewportWidth: Float, viewportHeight: Float) {
        attribute("width", width, androidNamespace)
        attribute("height", height, androidNamespace)
        attribute("viewportWidth", viewportWidth.toString(), androidNamespace)
        attribute("viewportHeight", viewportHeight.toString(), androidNamespace)
    }

    protected open fun startVector() {
        startTag("vector")
    }

    protected open fun endVector() {
        endTag("vector")
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
        startTag("group")
        attribute("scaleX", scaleX.toString(), androidNamespace)
        attribute("scaleY", scaleY.toString(), androidNamespace)
        attribute("translateX", translateX.toString(), androidNamespace)
        attribute("translateY", translateY.toString(), androidNamespace)
        attribute("rotation", rotation.toString(), androidNamespace)
        attribute("pivotX", pivotX.toString(), androidNamespace)
        attribute("pivotY", pivotY.toString(), androidNamespace)
    }

    fun endGroup() {
        endTag("group")
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
        attribute("pathData", pathData, androidNamespace)
        attribute("strokeLineJoin", strokeLineJoin, androidNamespace)
        attribute("strokeWidth", strokeWidth.toString(), androidNamespace)
        attribute("fillColor", fillColor, androidNamespace)
        attribute("strokeColor", strokeColor, androidNamespace)
        attribute("fillType", fillType, androidNamespace)
        attribute("strokeLineCap", strokeLineCap, androidNamespace)
        attribute("fillAlpha", fillAlpha.toString(), androidNamespace)
        attribute("strokeAlpha", strokeAlpha.toString(), androidNamespace)
        endTag("path")
    }
}