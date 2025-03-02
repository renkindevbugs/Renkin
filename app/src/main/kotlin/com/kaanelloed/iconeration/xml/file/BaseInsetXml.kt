package com.kaanelloed.iconeration.xml.file

abstract class BaseInsetXml: BaseVectorXml() {
    protected open fun startInset() {
        startTag("inset")
    }

    protected open fun endInset() {
        endTag("inset")
    }

    fun inset(insetBottom: Float, insetLeft: Float, insetRight: Float, insetTop: Float) {
        attribute("insetBottom", insetBottom.toString(), androidNamespace)
        attribute("insetLeft", insetLeft.toString(), androidNamespace)
        attribute("insetRight", insetRight.toString(), androidNamespace)
        attribute("insetTop", insetTop.toString(), androidNamespace)
    }

    fun bitmap(drawable: String, insetBottom: Float, insetLeft: Float, insetRight: Float, insetTop: Float) {
        attribute("drawable", drawable, androidNamespace)
        inset(insetBottom, insetLeft, insetRight, insetTop)
    }
}