package dev.renkinProject.renkin.xml.file

/**
 * A `<layer-list>` drawable stacking bitmap layers in order. Used for copied live-clock
 * icons: launchers with `<dynamic-clock>` support rotate the hand layers to the real time,
 * every other launcher draws the stack as-is — the static fallback is inherent in the format.
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
        attribute("drawable", "@drawable/$drawableName", androidNamespace)
        endTag("item")
    }

    override fun readAndClose(): ByteArray {
        endTag("layer-list")
        return super.readAndClose()
    }
}
