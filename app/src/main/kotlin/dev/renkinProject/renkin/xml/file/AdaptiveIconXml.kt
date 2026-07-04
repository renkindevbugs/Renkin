package dev.renkinProject.renkin.xml.file

class AdaptiveIconXml: InsetWrapperXml() {
    init {
        initialize()
    }

    override fun initialize() {
        super.initialize()
        namespace("android", androidNamespace)
        startTag("adaptive-icon")
    }

    fun foreground(drawableName: String) {
        startForeground()
        attribute("drawable", "@drawable/${drawableName}_foreground", androidNamespace)
        endForeground()
    }

    fun background(value: String) {
        startBackground()
        attribute("drawable", value, androidNamespace)
        endBackground()
    }

    /** The themed-icon layer: launchers tint its alpha silhouette with the wallpaper colours. */
    fun monochrome(value: String) {
        startTag("monochrome")
        attribute("drawable", value, androidNamespace)
        endTag("monochrome")
    }

    fun startForeground() {
        startTag("foreground")
    }

    fun endForeground() {
        endTag("foreground")
    }

    fun startBackground() {
        startTag("background")
    }

    fun endBackground() {
        endTag("background")
    }

    override fun readAndClose(): ByteArray {
        endTag("adaptive-icon")
        return super.readAndClose()
    }
}