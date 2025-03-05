package dev.alembiconsProject.alembicons.xml.file

class AdaptiveIconXml: BaseInsetXml() {
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

    public override fun startInset() {
        super.startInset()
    }

    public override fun endInset() {
        super.endInset()
    }

    public override fun startVector() {
        super.startVector()
    }

    public override fun endVector() {
        super.endVector()
    }

    override fun readAndClose(): ByteArray {
        endTag("adaptive-icon")
        return super.readAndClose()
    }
}