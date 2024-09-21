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

    fun dynamicClock(drawableName: String
                     , defaultHour: String
                     , defaultMinute: String
                     , hourLayerIndex: String
                     , minuteLayerIndex: String) {
        startTag("dynamic-clock")
        attribute("drawable", drawableName)
        attribute("defaultHour", defaultHour)
        attribute("defaultMinute", defaultMinute)
        attribute("hourLayerIndex", hourLayerIndex)
        attribute("minuteLayerIndex", minuteLayerIndex)
        endTag("dynamic-clock")
    }

    override fun readAndClose(): ByteArray {
        endTag("resources")
        return super.readAndClose()
    }
}