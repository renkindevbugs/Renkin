package dev.renkinProject.renkin.xml.file

/**
 * The `appmap.xml` mapping used by the GO Launcher family and Solo-era launchers: one
 * `<item class="activity" name="drawable"/>` per themed app. Same data as appfilter.xml,
 * older syntax — the pack's manifest advertises these launchers, so it must also speak
 * their format or they'd list the pack and then apply nothing.
 */
class AppMapXml : XmlMemoryFile() {
    init {
        initialize()
    }

    override fun initialize() {
        super.initialize()
        startTag("appmap")
    }

    fun item(activityName: String, drawableName: String) {
        startTag("item")
        attribute("class", activityName)
        attribute("name", drawableName)
        endTag("item")
    }

    override fun readAndClose(): ByteArray {
        endTag("appmap")
        return super.readAndClose()
    }
}
