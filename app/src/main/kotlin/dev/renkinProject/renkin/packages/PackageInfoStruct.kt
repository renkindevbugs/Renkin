package dev.renkinProject.renkin.packages

import android.graphics.drawable.Drawable
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.drawable.IconPackDrawable
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
    /**
     * Package name of the icon pack [createdIcon] was taken from. Null/empty when the icon
     * isn't from a pack (app-icon, app-name, upload, hand-edited vector or fallback styling).
     * Persisted so packs can be ordered by how often they're used in the per-app picker.
     */
    val sourcePackName: String? = null,
    /** Non-localized (English) app name for search matching. Falls back to [appName] if unavailable. */
    val originalName: String = appName,
    /**
     * True when [createdIcon] was produced by the pack's fallback styling (neither pack themed this
     * app), not a real pack match. Transient — recomputed on refresh, not persisted.
     */
    val isFallback: Boolean = false,
    /**
     * True when [createdIcon] came from a bulk refresh and hasn't been built/saved yet — the only
     * icons a later refresh may replace. Hand-picked icons, and anything loaded from the DB
     * (i.e. built or saved), are locked. Transient: not persisted, so a restart locks everything.
     */
    val isRefreshMade: Boolean = false,
    /**
     * True when [createdIcon] was hand-picked/edited by the user (per-app dialog, upload,
     * vector, watch-apply) rather than produced by a bulk refresh. Persisted — splits the
     * global-options grid into generated vs custom icons across restarts.
     */
    val isCustom: Boolean = false,
    /** Existing row whose generated-vs-custom origin predates the persisted classification. */
    val isLegacy: Boolean = false,
    /** Unmodified icon persisted to Room; [createdIcon] is its current global render. */
    val baseIcon: IconPackDrawable? = createdIcon,
    /**
     * Attribution reference for icons picked from an online FOSS library: the source file's
     * public URL. Informational only — the drawable itself is stored; this records origin.
     */
    val sourceUrl: String? = null
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
        sourcePackName: String? = this.sourcePackName,
        isFallback: Boolean = this.isFallback,
        isRefreshMade: Boolean = this.isRefreshMade,
        isCustom: Boolean = this.isCustom,
        isLegacy: Boolean = this.isLegacy,
        baseIcon: IconPackDrawable? = this.baseIcon,
        sourceUrl: String? = this.sourceUrl
    ): PackageInfoStruct =
        PackageInfoStruct(appName, packageName, activityName, icon, iconID, createdIcon, internalVersion + 1, calendarEnabled, calendarPrefix, calendarPackName, sourcePackName, originalName, isFallback, isRefreshMade, isCustom, isLegacy, baseIcon, sourceUrl)

    // Clearing the icon (createdIcon == null) also drops the recorded source pack and the
    // refresh-made flag, so a removed icon never lingers in the usage counts.
    fun changeExport(
        createdIcon: IconPackDrawable?,
        isFallback: Boolean = false,
        sourcePackName: String? = this.sourcePackName,
        isRefreshMade: Boolean = this.isRefreshMade,
        isCustom: Boolean = this.isCustom,
        isLegacy: Boolean = this.isLegacy,
        baseIcon: IconPackDrawable? = createdIcon,
        // A replaced icon must not inherit the previous one's online attribution, so the
        // reference travels explicitly with each new icon (null unless it came from a library).
        sourceUrl: String? = null
    ): PackageInfoStruct =
        copyWith(
            createdIcon = createdIcon,
            isFallback = isFallback,
            sourcePackName = if (createdIcon == null) null else sourcePackName,
            isRefreshMade = if (createdIcon == null) false else isRefreshMade,
            isCustom = if (createdIcon == null) false else isCustom,
            isLegacy = if (createdIcon == null) false else isLegacy,
            baseIcon = if (createdIcon == null) null else baseIcon,
            sourceUrl = if (createdIcon == null) null else sourceUrl
        )

    /** Replaces only the derived global render and keeps the persisted base untouched. */
    fun changeRenderedIcon(renderedIcon: IconPackDrawable?): PackageInfoStruct =
        copyWith(createdIcon = renderedIcon)

    /** The built/saved copy of this icon: identical, but no longer refresh-replaceable. */
    fun locked(): PackageInfoStruct = copyWith(isRefreshMade = false)

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
