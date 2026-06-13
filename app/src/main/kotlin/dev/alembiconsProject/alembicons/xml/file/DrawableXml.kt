package dev.alembiconsProject.alembicons.xml.file

class DrawableXml: XmlMemoryFile() {
    init {
        initialize()
    }

    override fun initialize() {
        super.initialize()
        startTag("resources")
        startTag("version")
        text("1")
        endTag("version")
        startTag("category")
        attribute("title", "All Apps")
        endTag("category")
    }

    fun item(drawableName: String) {
        startTag("item")
        attribute("drawable", drawableName)
        endTag("item")
    }

    override fun readAndClose(): ByteArray {
        endTag("resources")
        return super.readAndClose()
    }
}