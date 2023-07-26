package com.kaanelloed.iconeration

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import java.text.Normalizer

class PackageInfoStruct: Comparable<PackageInfoStruct> {
    lateinit var appName: String
    lateinit var packageName: String
    lateinit var activityName: String
    lateinit var icon: Drawable
    var iconID: Int = 0
    lateinit var genIcon: Bitmap
    lateinit var source: PackageSource

    override fun equals(other: Any?): Boolean {
        if (other is PackageInfoStruct) {
            return packageName == other.packageName && activityName == other.activityName
        }

        return false
    }

    override fun compareTo(other: PackageInfoStruct): Int = when {
        this.appName != other.appName -> this.normalizeName().lowercase() compareTo other.normalizeName().lowercase() // compareTo() in the infix form
        else -> 0
    }

    fun getFileName(): String {
        return packageName.replace('.', '_')
    }

    fun normalizeName(): String {
        return removeDiacritics(appName)
    }

    private fun removeDiacritics(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replace("\\p{Mn}+".toRegex(), "")
    }

    enum class PackageSource {
        Device, IconPack
    }
}