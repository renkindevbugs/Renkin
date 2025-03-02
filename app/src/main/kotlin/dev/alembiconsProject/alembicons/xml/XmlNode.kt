package dev.alembiconsProject.alembicons.xml

class XmlNode(val index: Int, val name: String, val depth: Int, val attributes: List<XmlAttribute>) {
    private val _children = mutableListOf<XmlNode>()
    private var _parent: XmlNode? = null

    val children: List<XmlNode>
        get() { return _children.toList() }

    val parent: XmlNode?
        get() { return _parent }

    fun addChild(node: XmlNode) {
        node._parent = this
        _children.add(node)
    }

    fun addChildren(nodes: List<XmlNode>) {
        for (node in nodes) {
            node._parent = this
        }

        _children.addAll(nodes)
    }

    fun containsAttribute(name: String): Boolean {
        return getAttribute(name) != null
    }

    fun getAttribute(name: String): XmlAttribute? {
        for (attribute in attributes) {
            if (attribute.name == name) {
                return attribute
            }
        }
        return null
    }

    fun getAttributeValue(name: String): String? {
        return getAttribute(name)?.value
    }

    fun containsTag(name: String): Boolean {
        return findFirstTag(name) != null
    }

    fun findFirstTag(name: String): XmlNode? {
        if (this.name == name) {
            return this
        }

        return findFirstChildTag(name)
    }

    fun containsChildTag(name: String): Boolean {
        return findFirstChildTag(name) != null
    }

    fun findFirstChildTag(name: String): XmlNode? {
        for (child in _children) {
            if (child.name == name)
                return child
        }

        return null
    }
}