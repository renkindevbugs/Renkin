package dev.renkinProject.renkin.xml.file

/**
 * A bitmap the launcher recolours with [tint] when it draws it. The bitmap only supplies the
 * shape (its alpha); a themed glyph exported this way follows the wallpaper colours.
 */
class TintedBitmapXml(private val drawableName: String, private val tint: String) : XmlMemoryFile() {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    init {
        initialize()
    }

    override fun initialize() {
        super.initialize()
        namespace("android", androidNamespace)
        startTag("bitmap")
        attribute("src", "@drawable/$drawableName", androidNamespace)
        attribute("tint", tint, androidNamespace)
        attribute("filter", "true", androidNamespace)
    }

    override fun readAndClose(): ByteArray {
        endTag("bitmap")
        return super.readAndClose()
    }
}
