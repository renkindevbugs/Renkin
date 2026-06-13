package dev.alembiconsProject.alembicons.xml.file

class InsetXml: BaseInsetXml() {
    init {
        initialize()
    }

    override fun initialize() {
        super.initialize()
        namespace("android", androidNamespace)
        startInset()
    }

    fun base64Drawable(drawable: String) {
        attribute("drawable", drawable, androidNamespace)
    }

    override fun readAndClose(): ByteArray {
        endInset()
        return super.readAndClose()
    }
}