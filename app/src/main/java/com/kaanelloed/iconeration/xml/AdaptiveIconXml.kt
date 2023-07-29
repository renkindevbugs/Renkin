package com.kaanelloed.iconeration.xml

class AdaptiveIconXml: XmlMemoryFile() {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    init {
        initialize()
    }

    override fun initialize() {
        super.initialize()
        namespace("android", androidNamespace)
        startTag("adaptive-icon")
    }

    fun foreground(drawableName: String) {
        startTag("foreground")
        attribute("drawable", "@drawable/${drawableName}_foreground", androidNamespace)
        endTag("foreground")
    }

    override fun readAndClose(): ByteArray {
        endTag("adaptive-icon")
        return super.readAndClose()
    }
}