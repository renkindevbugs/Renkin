package com.kaanelloed.iconeration

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import java.text.Normalizer

class PackageInfoStruct(
    val appName: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable,
    val iconID: Int,
    val export: Export? = null,
    val internalVersion: Int = 0
) : Comparable<PackageInfoStruct> {
    override fun equals(other: Any?): Boolean {
        if (other is PackageInfoStruct) {
            return packageName == other.packageName && activityName == other.activityName && other.internalVersion == internalVersion
        }

        return false
    }

    override fun compareTo(other: PackageInfoStruct): Int = when {
        this.appName != other.appName -> this.normalizeName().lowercase() compareTo other.normalizeName().lowercase() // compareTo() in the infix form
        else -> 0
    }

    fun changeExport(
        export: Export
    ): PackageInfoStruct  {
        return PackageInfoStruct(appName, packageName, activityName, icon, iconID, export, internalVersion + 1)
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

    enum class ExportType {
        PNG, XML
    }

    class Export(
        val type: ExportType
        , val bitmap: Bitmap?
        , val vector: VectorHandler?
    ) {
        constructor(bitmap: Bitmap) : this(ExportType.PNG, bitmap, null)
        constructor(bitmap: Bitmap, vector: VectorHandler) : this(ExportType.XML, bitmap, vector)
    }
}