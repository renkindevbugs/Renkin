package com.kaanelloed.iconeration.xml

import android.util.Xml
import com.reandroid.apk.AndroidFrameworks
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

class XMLEncoder(private val packageBlock: PackageBlock) {
    fun encodeToSource(xmlFile: XmlMemoryFile, name: String): ByteInputSource {
        return ByteInputSource(encode(xmlFile.getBytes()), name)
    }

    private fun encode(bytes: ByteArray): ByteArray {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        return encode(parser)
    }

    private fun encode(parser: XmlPullParser): ByteArray {
        val doc = ResXmlDocument()
        doc.packageBlock = packageBlock
        doc.parse(parser)

        return doc.bytes
    }
}