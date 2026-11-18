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

    /**
     * A single-item layer list, not a bare drawable reference: the Moto launcher casts a pack icon's
     * foreground to [android.graphics.drawable.LayerDrawable] when it builds its animated clock icon,
     * and throws - dropping the whole icon - for anything else (see ClockDrawableWrapper).
     */
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
        startTag("layer-list")
        startTag("item")
    }

    fun endForeground() {
        endTag("item")
        endTag("layer-list")
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