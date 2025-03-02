package dev.alembiconsProject.alembicons.xml

import org.xmlpull.v1.XmlPullParser

class XmlParser {
    private val nodes = ArrayDeque<XmlNode>()

    private fun parse(parser: XmlPullParser): XmlNode {
        var id = 0
        val document = XmlNode(id++, "document", 0, emptyList())

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                nodes.addLast(XmlNode(id++, parser.name, parser.depth, parseAttributes(parser)))
            }
            parser.next()
        }

        var parent = document
        var current = document

        while (nodes.size > 0) {
            val node = nodes.removeFirst()

            if (node.depth < current.depth) {
                parent = parent.parent!!
            }
            if (node.depth > current.depth) {
                parent = current
            }

            parent.addChild(node)
            current = node
        }

        return document
    }

    private fun parseAttributes(parser: XmlPullParser): List<XmlAttribute> {
        val attributes = mutableListOf<XmlAttribute>()

        for (i in 0 until parser.attributeCount) {
            val namespace = parser.getAttributeNamespace(i)
            val name = parser.getAttributeName(i)
            val value = parser.getAttributeValue(i)
            val type = parser.getAttributeType(i)

            attributes.add(XmlAttribute(namespace, name, value, type))
        }

        return attributes.toList()
    }

    companion object {
        private fun parse(pullParser: XmlPullParser): XmlNode {
            val parser = XmlParser()
            return parser.parse(pullParser)
        }

        fun XmlPullParser.toXmlNode(): XmlNode {
            return parse(this)
        }
    }
}