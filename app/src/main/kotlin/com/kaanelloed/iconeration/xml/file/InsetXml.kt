package com.kaanelloed.iconeration.xml.file

class InsetXml: BaseInsetXml() {
    init {
        initialize()
    }

    override fun initialize() {
        super.initialize()
        namespace("android", androidNamespace)
        startInset()
    }

    override fun startVector() {
        super.startVector()
    }

    override fun endVector() {
        super.endVector()
    }

    override fun readAndClose(): ByteArray {
        endInset()
        return super.readAndClose()
    }
}