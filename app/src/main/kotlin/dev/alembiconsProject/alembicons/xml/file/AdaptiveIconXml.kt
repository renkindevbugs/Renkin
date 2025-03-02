package dev.alembiconsProject.alembicons.xml.file

class AdaptiveIconXml: BaseInsetXml() {
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

    fun background(value: String) {
        startTag("background")
        attribute("drawable", value, androidNamespace)
        endTag("background")
    }

    override fun startInset() {
        super.startInset()
    }

    override fun endInset() {
        super.endInset()
    }

    override fun readAndClose(): ByteArray {
        endTag("adaptive-icon")
        return super.readAndClose()
    }
}