package dev.renkinProject.renkin.xml

import org.xmlpull.v1.XmlPullParser

class XmlParser {
    private val nodes = ArrayDeque<XmlNode>()

    private fun parse(parser: XmlPullParser, maxNodes: Int, maxDepth: Int, checkActive: () -> Unit): XmlNode {
        var id = 0
        val document = XmlNode(id++, "document", 0, emptyList())

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            checkActive()
            if (parser.eventType == XmlPullParser.START_TAG) {
                require(id < maxNodes && parser.depth <= maxDepth) { "XML exceeds decoding limits" }
                nodes.addLast(XmlNode(id++, parser.name, parser.depth, parseAttributes(parser)))
            }
            parser.next()
        }

        var parent = document
        var current = document

        while (nodes.size > 0) {
            checkActive()
            val node = nodes.removeFirst()

            if (node.depth > current.depth) {
                parent = current
            } else {
                // A closing run can drop several levels at once; climb to this node's own level.
                while (parent.depth >= node.depth) parent = parent.parent!!
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
        private fun parse(pullParser: XmlPullParser, maxNodes: Int, maxDepth: Int, checkActive: () -> Unit): XmlNode {
            val parser = XmlParser()
            return parser.parse(pullParser, maxNodes, maxDepth, checkActive)
        }

        fun XmlPullParser.toXmlNode(
            maxNodes: Int = Int.MAX_VALUE,
            maxDepth: Int = Int.MAX_VALUE,
            checkActive: () -> Unit = {}
        ): XmlNode {
            return parse(this, maxNodes, maxDepth, checkActive)
        }
    }
}
