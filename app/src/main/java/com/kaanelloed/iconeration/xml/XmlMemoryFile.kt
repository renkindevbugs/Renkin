package com.kaanelloed.iconeration.xml

import android.util.Xml
import java.io.ByteArrayOutputStream

abstract class XmlMemoryFile {
    private val encoding = "UTF-8"
    private val stream = ByteArrayOutputStream()
    private val xmlSerializer = Xml.newSerializer()

    private var bytes: ByteArray? = null

    protected open fun initialize() {
        xmlSerializer.setOutput(stream, encoding)
        xmlSerializer.startDocument(encoding, true)
    }

    protected fun startTag(name: String, namespace: String? = null) {
        xmlSerializer.startTag(namespace, name)
    }

    protected fun endTag(name: String, namespace: String? = null) {
        xmlSerializer.endTag(namespace, name)
    }

    protected fun attribute(name: String, value: String, namespace: String? = null) {
        xmlSerializer.attribute(namespace, name, value)
    }

    protected fun text(text: String) {
        xmlSerializer.text(text)
    }

    protected fun namespace(prefix: String, namespace: String) {
        xmlSerializer.setPrefix(prefix, namespace)
    }

    open fun readAndClose():ByteArray {
        xmlSerializer.endDocument()
        bytes = stream.toByteArray()
        stream.close()

        return bytes!!
    }

    fun getBytes(): ByteArray {
        if (bytes == null) {
            readAndClose()
        }

        return bytes!!
    }

    fun close() {
        xmlSerializer.flush()
        stream.close()
    }
}