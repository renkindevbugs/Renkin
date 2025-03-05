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

    public override fun startVector() {
        super.startVector()
    }

    public override fun endVector() {
        super.endVector()
    }

    override fun readAndClose(): ByteArray {
        endInset()
        return super.readAndClose()
    }
}