package com.kaanelloed.iconeration.extension

import com.kaanelloed.iconeration.xml.XmlAttribute
import org.xmlpull.v1.XmlPullParser

fun XmlPullParser.parseUntil(name: String): Boolean {
    while (this.eventType != XmlPullParser.END_DOCUMENT) {
        if (this.eventType == XmlPullParser.START_TAG) {
            if (this.name == name) {
                return true
            }
        }
        this.next()
    }

    return false
}

fun XmlPullParser.parseUntil(names: List<String>): String? {
    while (this.eventType != XmlPullParser.END_DOCUMENT) {
        if (this.eventType == XmlPullParser.START_TAG) {
            if (names.contains(name)) {
                return name
            }
        }
        this.next()
    }

    return null
}

fun XmlPullParser.isAtStartTag(): Boolean {
    return eventType == XmlPullParser.START_TAG
}

fun XmlPullParser.isAtEndDocument(): Boolean {
    return eventType == XmlPullParser.END_DOCUMENT
}

fun XmlPullParser.getAttributes(): List<XmlAttribute> {
    val attributes = mutableListOf<XmlAttribute>()

    for (i in 0 until attributeCount) {
        val namespace = getAttributeNamespace(i)
        val name = getAttributeName(i)
        val value = getAttributeValue(i)
        val type = getAttributeType(i)

        attributes.add(XmlAttribute(namespace, name, value, type))
    }

    return attributes.toList()
}

fun XmlPullParser.safeNext() {
    if (!isAtEndDocument()) {
        next()
    }
}