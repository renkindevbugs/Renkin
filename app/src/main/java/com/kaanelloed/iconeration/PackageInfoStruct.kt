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
    val versionCode: Long,
    val versionName: String,
    val source: PackageSource,
    val exportType: ExportType = ExportType.PNG,
    val genIcon: Bitmap? = null,
    val vector: VectorHandler? = null,
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
        exportType: ExportType = ExportType.PNG
        , genIcon: Bitmap? = null
        , vector: VectorHandler? = null
    ): PackageInfoStruct  {
        return PackageInfoStruct(appName, packageName, activityName, icon, iconID, versionCode, versionName, source, exportType, genIcon, vector, internalVersion + 1)
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

    enum class ExportType {
        PNG, XML
    }
}