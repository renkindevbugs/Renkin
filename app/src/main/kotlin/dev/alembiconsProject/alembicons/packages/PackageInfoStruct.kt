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
    val calendarEnabled: Boolean = false,
    /** Drawable prefix used for calendar day rotation (e.g. `"google_cal_"`). Null = not set. */
    val calendarPrefix: String? = null,
    /** Package name of the icon pack the calendar drawables come from. Null = not set. */
    val calendarPackName: String? = null
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

    fun changeExport(createdIcon: IconPackDrawable?): PackageInfoStruct =
        PackageInfoStruct(appName, packageName, activityName, icon, iconID, createdIcon, internalVersion + 1, calendarEnabled, calendarPrefix, calendarPackName)

    fun changeCalendar(calendarEnabled: Boolean, calendarPrefix: String? = this.calendarPrefix, calendarPackName: String? = this.calendarPackName): PackageInfoStruct =
        PackageInfoStruct(appName, packageName, activityName, icon, iconID, createdIcon, internalVersion + 1, calendarEnabled, calendarPrefix, calendarPackName)

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