package com.kaanelloed.iconeration.xml

import android.util.Xml
import java.io.ByteArrayOutputStream

abstract class XmlMemoryFile {
    private val encoding = "UTF-8"
    private val stream = ByteArrayOutputStream()
    private val xmlSerializer = Xml.newSerializer()

    protected open fun initialize() {
        xmlSerializer.setOutput(stream, encoding)
        xmlSerializer.startDocument(encoding, true)
    }

    protected fun startTag(name: String) {
        xmlSerializer.startTag(null, name)
    }

    protected fun endTag(name: String) {
        xmlSerializer.endTag(null, name)
    }

    protected fun attribute(name: String, value: String) {
        xmlSerializer.attribute(null, name, value)
    }

    protected fun text(text: String) {
        xmlSerializer.text(text)
    }

    open fun readAndClose():ByteArray {
        xmlSerializer.endDocument()
        val bytes = stream.toByteArray()
        stream.close()

        return bytes
    }

    fun close() {
        xmlSerializer.flush()
        stream.close()
    }
}