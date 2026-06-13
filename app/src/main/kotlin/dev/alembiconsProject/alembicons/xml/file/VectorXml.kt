package dev.alembiconsProject.alembicons.xml.file

class VectorXml: BaseVectorXml() {
    init {
        initialize()
    }

    override fun initialize() {
        super.initialize()
        namespace("android", androidNamespace)
        startVector()
    }

    override fun readAndClose(): ByteArray {
        endVector()
        return super.readAndClose()
    }
}