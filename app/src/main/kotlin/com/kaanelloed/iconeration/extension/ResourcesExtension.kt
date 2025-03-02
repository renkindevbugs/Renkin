package com.kaanelloed.iconeration.extension

import android.annotation.SuppressLint
import android.content.res.Resources
import android.content.res.Resources.Theme
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat

@SuppressLint("DiscouragedApi")
fun Resources.getIdentifierByName(name: String, defType: String, defPackage: String): Int {
    return getIdentifier(name, defType, defPackage)
}

fun Resources.getXmlOrNull(resourceId: Int): XmlResourceParser? {
    return try {
        this.getXml(resourceId)
    } catch (e: Resources.NotFoundException) {
        null
    }
}

fun Resources.getDrawableOrNull(resourceId: Int, theme: Theme? = null): Drawable? {
    return try {
        ResourcesCompat.getDrawable(this, resourceId, theme)
    } catch (e: Resources.NotFoundException) {
        null
    }
}