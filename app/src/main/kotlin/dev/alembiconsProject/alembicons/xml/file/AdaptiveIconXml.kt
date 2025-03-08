package dev.alembiconsProject.alembicons.xml.file

class AdaptiveIconXml: InsetWrapperXml() {
    init {
        initialize()
    }

    override fun initialize() {
        super.initialize()
        namespace("android", androidNamespace)
        startTag("adaptive-icon")
    }

    fun foreground(drawableName: String) {
        startForeground()
        attribute("drawable", "@drawable/${drawableName}_foreground", androidNamespace)
        endForeground()
    }

    fun background(value: String) {
        startBackground()
        attribute("drawable", value, androidNamespace)
        endBackground()
    }

    fun startForeground() {
        startTag("foreground")
    }

    fun endForeground() {
        endTag("foreground")
    }

    fun startBackground() {
        startTag("background")
    }

    fun endBackground() {
        endTag("background")
    }

    override fun readAndClose(): ByteArray {
        endTag("adaptive-icon")
        return super.readAndClose()
    }
}