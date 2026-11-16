package dev.renkinProject.renkin.xml.file

/**
 * `res/xml/grayscale_icon_map.xml`: the themed-icon source format of Lawnchair and Kvaesitso.
 * Each entry points at a one-colour glyph the launcher tints with the wallpaper colours.
 * Lawnchair looks up the exact component first and then the package-only entry, so the first
 * glyph of each package also gets a package-only entry for activity aliases the pack never saw.
 */
class GrayscaleIconMapXml : XmlMemoryFile() {
    private val packagesWithFallback = mutableSetOf<String>()

    var isEmpty = true
        private set

    init {
        initialize()
    }

    override fun initialize() {
        super.initialize()
        startTag("icons")
    }

    fun item(packageName: String, activityName: String, drawableName: String) {
        icon(packageName, activityName, drawableName)
        if (packagesWithFallback.add(packageName)) icon(packageName, null, drawableName)
        isEmpty = false
    }

    private fun icon(packageName: String, activityName: String?, drawableName: String) {
        startTag("icon")
        attribute("package", packageName)
        if (activityName != null) attribute("component", activityName)
        attribute("drawable", "@drawable/$drawableName")
        endTag("icon")
    }

    override fun readAndClose(): ByteArray {
        endTag("icons")
        return super.readAndClose()
    }
}
