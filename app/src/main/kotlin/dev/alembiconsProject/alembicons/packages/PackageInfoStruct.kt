package dev.alembiconsProject.alembicons.packages

import android.graphics.drawable.Drawable
import dev.alembiconsProject.alembicons.data.InstalledApplication
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import java.text.Normalizer

class PackageInfoStruct(
    val appName: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable,
    val iconID: Int,
    val createdIcon: IconPackDrawable? = null,
    val internalVersion: Int = 0,
    // The Modifier-tab scale baked into createdIcon, remembered so re-editing
    // restores the slider instead of starting from 100%. Session-only for now.
    val iconScale: Float = 1f
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
        createdIcon: IconPackDrawable?,
        iconScale: Float = this.iconScale
    ): PackageInfoStruct {
        return PackageInfoStruct(appName, packageName, activityName, icon, iconID, createdIcon, internalVersion + 1, iconScale)
    }

    fun getFileName(): String {
        return packageName.replace('.', '_')
    }

    fun toInstalledApplication(): InstalledApplication {
        return InstalledApplication(packageName, activityName, iconID)
    }

    private fun normalizeName(): String {
        return removeDiacritics(appName)
    }

    private fun removeDiacritics(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replace("\\p{Mn}+".toRegex(), "")
    }

    override fun hashCode(): Int {
        var result = packageName.hashCode()
        result = 31 * result + activityName.hashCode()
        return result
    }
}