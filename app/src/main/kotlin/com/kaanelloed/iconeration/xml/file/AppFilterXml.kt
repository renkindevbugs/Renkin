package com.kaanelloed.iconeration.xml.file

class AppFilterXml: XmlMemoryFile() {
    init {
        initialize()
    }

    override fun initialize() {
        super.initialize()
        startTag("resources")
    }

    fun item(packageName: String, activityName: String, drawableName: String) {
        startTag("item")
        attribute("component", "ComponentInfo{${packageName}/${activityName}}")
        attribute("drawable", drawableName)
        endTag("item")
    }

    override fun readAndClose(): ByteArray {
        endTag("resources")
        return super.readAndClose()
    }
}