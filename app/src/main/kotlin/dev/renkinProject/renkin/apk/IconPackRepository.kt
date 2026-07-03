package dev.renkinProject.renkin.apk

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawCalendar
import dev.renkinProject.renkin.data.RawElement
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.packages.ApplicationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns everything about installed icon packs: the pack list, their app-filter elements,
 * the per-app drawables resolved from them, and the calendar icons derived from them.
 * [ApplicationProvider] delegates here; icon generation and pack building query it.
 *
 * [iconPacks] / [iconPackLoaded] are Compose state so the UI re-reads them once packs
 * finish loading (e.g. the edit dialog's icon-pack browser) instead of capturing the
 * empty initial list.
 */
class IconPackRepository(private val context: Context) {
    var iconPacks: List<IconPack> by mutableStateOf(listOf())
        private set
    var iconPackLoaded: Boolean by mutableStateOf(false)
        private set

    private var appFilterElements: Map<IconPack, List<RawElement>> = emptyMap()
    private var installedApplications: List<InstalledApplication> = listOf()

    var calendarIcon: Map<InstalledApplication, String> = mapOf()
        private set
    var calendarIconsDrawable: Map<String, Drawable> = emptyMap()
        private set

    private val appManager: ApplicationManager by lazy { ApplicationManager(context) }

    suspend fun load() = withContext(Dispatchers.Default) {
        iconPackLoaded = false
        // Drop our own generated pack — it only ever holds icons we just built, so offering
        // it as an icon source (or a watch target) is pointless and just clutters the lists.
        iconPacks = appManager.getIconPacks().filter { it.packageName != IconPackBuilder.PACKAGE_NAME }
        loadAppFilterElements()
    }

    private fun loadAppFilterElements() {
        val map = mutableMapOf<IconPack, List<RawElement>>()

        installedApplications = appManager.getAllInstalledApplications()

        for (iconPack in iconPacks) {
            map[iconPack] = appManager.getAppFilterRawElements(iconPack.packageName, installedApplications)
        }

        appFilterElements = map
        iconPackLoaded = true
    }

    /** The pack's classic fallback styling (iconback/mask/upon/scale) for unthemed apps. */
    fun getIconPackFallback(iconPack: String): dev.renkinProject.renkin.data.IconPackFallback {
        if (iconPack == "") return dev.renkinProject.renkin.data.IconPackFallback()
        return appManager.getIconPackFallback(iconPack)
    }

    /** Drawables every installed app has in [iconPack], keyed by app. */
    fun getAppDrawables(iconPack: String): Map<InstalledApplication, ResourceDrawable> {
        if (iconPack == "") return emptyMap()
        val entry = appFilterElements.entries.find { it.key.packageName == iconPack } ?: return emptyMap()

        return appManager.getDrawableFromAppFilterElements(
            iconPack,
            installedApplications,
            entry.value
        )
    }

    private fun getAppDrawable(app: InstalledApplication, iconPack: String): Map<InstalledApplication, ResourceDrawable> {
        if (iconPack == "") return emptyMap()
        val entry = appFilterElements.entries.find { it.key.packageName == iconPack } ?: return emptyMap()

        return appManager.getDrawableFromAppFilterElements(
            iconPack,
            listOf(app),
            entry.value
        )
    }

    fun retrieveCalendarIcons(iconPackageName: String) {
        val entry = appFilterElements.entries.find { it.key.packageName == iconPackageName }

        val packApps = entry?.value ?: listOf()
        calendarIcon = appManager.getCalendarApplications(installedApplications, packApps)
        calendarIconsDrawable =
            appManager.getCalendarFromAppFilterElements(
                iconPackageName,
                packApps
            )
    }

    /**
     * Distinct calendar-day prefixes [iconPackageName] declares in its appfilter (`<calendar>`
     * entries), e.g. `{"google_cal_", "samsung_cal_"}`. Used to badge only genuine calendar
     * icons in the browser instead of anything whose name happens to end in a number. Reads
     * the already-parsed [appFilterElements] — no I/O.
     */
    fun calendarPrefixes(iconPackageName: String): Set<String> {
        val entry = appFilterElements.entries.find { it.key.packageName == iconPackageName } ?: return emptySet()
        return entry.value.filterIsInstance<RawCalendar>().map { it.prefix }.toSet()
    }

    /**
     * Whether [prefix] is a genuine calendar day-rotation set in [iconPackageName] — used to badge
     * only real calendars in the browser, not anything whose name happens to end in a number
     * (authenticator_, calculator_, …). True when the pack declares it as a `<calendar>`, or when
     * the drawables span a full month: a high day (28, present in every month) must exist, which a
     * short `_1.._3` series can't fake. Cheap — resolves resource ids only, no drawable decode.
     */
    fun isCalendarPrefix(iconPackageName: String, prefix: String): Boolean {
        if (prefix.isEmpty()) return false
        if (prefix in calendarPrefixes(iconPackageName)) return true
        val hasFirst = appManager.hasDrawable(iconPackageName, prefix + "1") ||
            appManager.hasDrawable(iconPackageName, prefix + "01")
        return hasFirst && appManager.hasDrawable(iconPackageName, prefix + "28")
    }

    /**
     * Returns the calendar prefix for [app] in [iconPackageName] (e.g. `"google_cal_"`),
     * or null if the pack doesn't declare a calendar entry for that app.
     * Reads from the already-loaded [appFilterElements] — no I/O.
     */
    fun calendarPrefixFor(app: InstalledApplication, iconPackageName: String): String? {
        val entry = appFilterElements.entries.find { it.key.packageName == iconPackageName } ?: return null
        val component = app.toComponentInfo()
        return entry.value.filterIsInstance<RawCalendar>().find { it.component == component }?.prefix
    }

    /**
     * Returns calendar icons (app→prefix map and prefix+day→drawable map) for the given
     * [appsWithPrefixes] in [iconPackageName]. The prefix is supplied by the caller — it was
     * derived from the drawable name the user picked, so it doesn't depend on appfilter.xml
     * having a `<calendar>` entry for that specific app.
     */
    fun calendarDataForPrefixes(
        iconPackageName: String,
        appsWithPrefixes: List<Pair<InstalledApplication, String>>
    ): Pair<Map<InstalledApplication, String>, Map<String, Drawable>> {
        if (appsWithPrefixes.isEmpty()) return emptyMap<InstalledApplication, String>() to emptyMap()

        val apps = appsWithPrefixes.map { it.first }
        // Synthesise RawCalendar entries from the user-chosen prefixes (no appfilter lookup).
        val fakeCalendars = appsWithPrefixes.map { (app, prefix) ->
            RawCalendar(component = app.toComponentInfo(), prefix = prefix)
        }

        val icons = appManager.getCalendarApplications(apps, fakeCalendars)
        val raw = appManager.getCalendarFromAppFilterElements(iconPackageName, fakeCalendars)

        // Fill missing calendar days so the icon appears on every date, even when the
        // source pack only has drawables for the app the icon was *designed* for.
        // Step 1: some packs zero-pad names (prefix01..prefix09); try that for any gap.
        // Step 2: any day still missing gets the first drawable found for that prefix.
        val filled = raw.toMutableMap()
        for ((_, prefix) in appsWithPrefixes) {
            for (i in 1..31) {
                val key = prefix + i
                if (key !in filled) {
                    val padded = appManager.getDrawableByName(iconPackageName, prefix + i.toString().padStart(2, '0'))
                    if (padded != null) filled[key] = padded
                }
            }
            val fallback = filled.entries.firstOrNull { it.key.startsWith(prefix) }?.value
            if (fallback != null) {
                for (i in 1..31) filled.putIfAbsent(prefix + i, fallback)
            }
        }

        return icons to filled
    }

    /**
     * Day numbers (1..31) the source pack is missing for [prefix], checking both the plain
     * (`prefix7`) and zero-padded (`prefix07`) naming. Used to warn before a build that a
     * calendar icon won't rotate cleanly because some days fall back to a repeated drawable.
     */
    fun missingCalendarDays(iconPackageName: String, prefix: String): List<Int> {
        if (prefix.isEmpty()) return emptyList()
        return (1..31).filter { day ->
            !appManager.hasDrawable(iconPackageName, prefix + day) &&
                !appManager.hasDrawable(iconPackageName, prefix + day.toString().padStart(2, '0'))
        }
    }

    /** One representative icon per pack (the pack's own icon, or the app's icon in that pack). */
    suspend fun getDropdownIcons(application: InstalledApplication?): Map<String, ResourceDrawable> =
        withContext(Dispatchers.Default) {
            val map = mutableMapOf<String, ResourceDrawable>()

            for (pack in iconPacks) {
                if (application == null) {
                    val icon = appManager.getResIcon(pack.packageName, pack.iconID)

                    if (icon != null) {
                        map[pack.packageName] = ResourceDrawable(pack.iconID, icon)
                    }
                } else {
                    val icons = getAppDrawable(application, pack.packageName)

                    if (icons.isNotEmpty()) {
                        map[pack.packageName] = icons[application]!!
                    }
                }
            }

            map
        }
}
