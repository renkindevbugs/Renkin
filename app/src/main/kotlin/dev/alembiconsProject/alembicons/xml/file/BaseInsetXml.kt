package dev.alembiconsProject.alembicons.xml.file

abstract class BaseInsetXml: BaseVectorXml() {
    protected open fun startInset() {
        startTag("inset")
    }

    protected open fun endInset() {
        endTag("inset")
    }

    fun inset(insetBottom: String, insetLeft: String, insetRight: String, insetTop: String) {
        attribute("insetBottom", insetBottom, androidNamespace)
        attribute("insetLeft", insetLeft, androidNamespace)
        attribute("insetRight", insetRight, androidNamespace)
        attribute("insetTop", insetTop, androidNamespace)
    }

    fun insetDrawable(drawable: String) {
        attribute("drawable", drawable, androidNamespace)
    }
}