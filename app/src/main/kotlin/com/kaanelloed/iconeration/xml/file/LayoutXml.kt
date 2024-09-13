package com.kaanelloed.iconeration.xml.file

class LayoutXml: XmlMemoryFile() {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    init {
        initialize()
    }

    override fun initialize() {
        super.initialize()
        namespace("android", androidNamespace)
        startTag("LinearLayout")

        attribute("layout_width", "match_parent", androidNamespace)
        attribute("layout_height", "match_parent", androidNamespace)
    }

    fun textView(text: String) {
        startTag("TextView")
        attribute("layout_width", "0dp", androidNamespace)
        attribute("layout_height", "wrap_content", androidNamespace)
        attribute("layout_weight", "1", androidNamespace)
        attribute("text", text, androidNamespace)
        endTag("TextView")
    }

    override fun readAndClose(): ByteArray {
        endTag("LinearLayout")
        return super.readAndClose()
    }
}