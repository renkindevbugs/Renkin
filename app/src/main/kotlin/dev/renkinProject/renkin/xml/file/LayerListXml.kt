package dev.renkinProject.renkin.xml.file

/**
 * Clock hands retain their level-driven rotation; only the underlying artwork is rasterized.
 */
class LayerListXml : XmlMemoryFile() {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    init {
        initialize()
    }

    override fun initialize() {
        super.initialize()
        namespace("android", androidNamespace)
        startTag("layer-list")
    }

    fun item(drawableName: String) {
        startTag("item")
        bitmap(drawableName)
        endTag("item")
    }

    fun rotatingItem(drawableName: String, fromDegrees: Float, toDegrees: Float, pivotX: Float, pivotY: Float) {
        startTag("item")
        startTag("rotate")
        attribute("fromDegrees", fromDegrees.toString(), androidNamespace)
        attribute("toDegrees", toDegrees.toString(), androidNamespace)
        attribute("pivotX", "${pivotX * 100}%", androidNamespace)
        attribute("pivotY", "${pivotY * 100}%", androidNamespace)
        bitmap(drawableName)
        endTag("rotate")
        endTag("item")
    }

    private fun bitmap(drawableName: String) {
        startTag("bitmap")
        attribute("src", "@drawable/$drawableName", androidNamespace)
        attribute("gravity", "fill", androidNamespace)
        attribute("filter", "true", androidNamespace)
        endTag("bitmap")
    }

    override fun readAndClose(): ByteArray {
        endTag("layer-list")
        return super.readAndClose()
    }
}
