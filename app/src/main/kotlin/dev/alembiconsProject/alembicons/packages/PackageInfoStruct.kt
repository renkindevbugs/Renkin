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
    val calendarPackName: String? = null,
    /** Non-localized (English) app name for search matching. Falls back to [appName] if unavailable. */
    val originalName: String = appName,
    /**
     * True when [createdIcon] was produced by the pack's fallback styling (neither pack themed this
     * app), not a real pack match. Transient — recomputed on refresh, not persisted.
     */
    val isFallback: Boolean = false
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

    // Single place that rebuilds the struct, so callers (changeExport/changeCalendar) only name
    // the fields they touch — this isn't a data class (custom equals), so there's no copy().
    // Every edit bumps internalVersion so the SnapshotStateList sees a distinct element.
    private fun copyWith(
        createdIcon: IconPackDrawable? = this.createdIcon,
        calendarEnabled: Boolean = this.calendarEnabled,
        calendarPrefix: String? = this.calendarPrefix,
        calendarPackName: String? = this.calendarPackName,
        isFallback: Boolean = this.isFallback
    ): PackageInfoStruct =
        PackageInfoStruct(appName, packageName, activityName, icon, iconID, createdIcon, internalVersion + 1, calendarEnabled, calendarPrefix, calendarPackName, originalName, isFallback)

    fun changeExport(createdIcon: IconPackDrawable?, isFallback: Boolean = false): PackageInfoStruct =
        copyWith(createdIcon = createdIcon, isFallback = isFallback)

    fun changeCalendar(calendarEnabled: Boolean, calendarPrefix: String? = this.calendarPrefix, calendarPackName: String? = this.calendarPackName): PackageInfoStruct =
        copyWith(calendarEnabled = calendarEnabled, calendarPrefix = calendarPrefix, calendarPackName = calendarPackName)

    /** True when this app opts into calendar day rotation with a usable prefix. */
    val hasCalendarIcon: Boolean get() = calendarEnabled && !calendarPrefix.isNullOrEmpty()

    /**
     * The pack the calendar day drawables should load from: the one the user picked the icon from,
     * falling back to [primaryPackName] (the global default) when no per-app pack was stored.
     */
    fun calendarSourcePack(primaryPackName: String): String =
        calendarPackName?.takeIf { it.isNotEmpty() } ?: primaryPackName

    /**
     * Stable identity ("packageName/activityName") used to diff against the saved/built pack
     * (builtKeys, updatedKeys) and as a LazyList item key. RenkinPackStore builds the same string
     * from its DbApplication rows, so the two match.
     */
    val key: String get() = "$packageName/$activityName"

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