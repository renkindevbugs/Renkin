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

    fun calendar(packageName: String, activityName: String, prefix: String) {
        startTag("calendar")
        attribute("component", "ComponentInfo{${packageName}/${activityName}}")
        attribute("prefix", prefix)
        endTag("calendar")
    }

    override fun readAndClose(): ByteArray {
        endTag("resources")
        return super.readAndClose()
    }
}