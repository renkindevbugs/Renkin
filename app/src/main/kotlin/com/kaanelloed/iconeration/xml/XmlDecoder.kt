package com.kaanelloed.iconeration.xml

import android.util.Base64
import android.util.Xml
import com.kaanelloed.iconeration.xml.XmlParser.Companion.toXmlNode
import java.io.ByteArrayInputStream

class XmlDecoder {
    private fun fromBase64(base64: String, base64Flag: Int = Base64.NO_WRAP): XmlNode {
        val bytes = Base64.decode(base64, base64Flag)

        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        return parser.toXmlNode()
    }

    companion object {
        fun fromBase64(base64: String, base64Flag: Int = Base64.NO_WRAP): XmlNode {
            return XmlDecoder().fromBase64(base64, base64Flag)
        }
    }
}