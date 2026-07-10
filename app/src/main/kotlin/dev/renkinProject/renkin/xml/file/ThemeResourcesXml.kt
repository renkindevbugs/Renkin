package dev.renkinProject.renkin.xml.file

/**
 * The `theme_resources.xml` descriptor of GO-style themes: pack label plus an `<AppIcons>`
 * list mapping components to drawables (appfilter.xml data in the GO schema). Wallpapers,
 * previews and iconback styling are legitimately optional and left out — the generated
 * pack's icons are already fully composed.
 */
class ThemeResourcesXml(label: String) : XmlMemoryFile() {

    init {
        initialize()
        startTag("Label")
        attribute("value", label)
        endTag("Label")
        startTag("Scale")
        attribute("factor", "1.0")
        endTag("Scale")
        startTag("AppIcons")
    }

    override fun initialize() {
        super.initialize()
        startTag("Theme")
        attribute("version", "1")
    }

    fun item(packageName: String, activityName: String, drawableName: String) {
        startTag("Item")
        attribute("component", "ComponentInfo{${packageName}/${activityName}}")
        attribute("drawable", drawableName)
        endTag("Item")
    }

    override fun readAndClose(): ByteArray {
        endTag("AppIcons")
        endTag("Theme")
        return super.readAndClose()
    }
}
