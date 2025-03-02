package com.kaanelloed.iconeration.xml

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class XmlSerializer {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"
    private val encoding = "UTF-8"
    private val stream = ByteArrayOutputStream()
    private val xmlSerializer = Xml.newSerializer()

    init {
        xmlSerializer.setOutput(stream, encoding)
        xmlSerializer.startDocument(encoding, true)
        xmlSerializer.setPrefix("android", androidNamespace)
    }

    private fun serialize(xmlNode: XmlNode): XmlPullParser {
        serializeNode(xmlNode)

        xmlSerializer.endDocument()
        val bytes = stream.toByteArray()
        stream.close()

        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        return parser
    }

    private fun serializeNode(xmlNode: XmlNode) {
        xmlSerializer.startTag(null, xmlNode.name)

        for (attribute in xmlNode.attributes) {
            xmlSerializer.attribute(attribute.namespace, attribute.name, attribute.value)
        }

        for (child in xmlNode.children) {
            serializeNode(child)
        }
    }

    companion object {
        private fun serialize(xmlNode: XmlNode): XmlPullParser {
            val serializer = XmlSerializer()
            return serializer.serialize(xmlNode)
        }

        fun XmlNode.toPullParser(): XmlPullParser {
            return serialize(this)
        }
    }
}