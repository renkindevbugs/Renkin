package dev.renkinProject.renkin.apk

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.IconPackFallback
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawCalendar
import dev.renkinProject.renkin.data.RawElement
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal suspend fun loadIsolatedAppFilters(
    packs: List<IconPack>,
    loader: (IconPack) -> List<RawElement>,
    onFailure: (IconPack, Exception) -> Unit = { _, _ -> }
): Map<IconPack, List<RawElement>> = coroutineScope {
    // Each pack's appfilter parses on its own worker: big packs (Lawnicons & co. carry
    // thousands of entries) no longer serialize cold start. Isolation is preserved — one
    // malformed pack is reported and skipped without affecting the others.
    val parsed = packs.map { pack ->
        async(Dispatchers.Default) {
            try {
                pack to loader(pack)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure(pack, error)
                null
            }
        }
    }.awaitAll()
    buildMap { parsed.filterNotNull().forEach { (pack, elements) -> put(pack, elements) } }
}

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
    private var appFilterElementsByPackage: Map<String, List<RawElement>> = emptyMap()
    private var installedApplications: List<InstalledApplication> = listOf()
    private val fallbackCache = mutableMapOf<String, IconPackFallback>()

    private val appManager: ApplicationManager by lazy { ApplicationManager(context) }

    /**
     * [installedApps] comes from the caller's already-loaded app list — re-scanning the
     * launcher here used to repeat the whole per-app label/icon work a second time at startup.
     */
    suspend fun load(installedApps: List<InstalledApplication>) = withContext(Dispatchers.Default) {
        iconPackLoaded = false
        synchronized(fallbackCache) { fallbackCache.clear() }
        // Drop our own generated pack — it only ever holds icons we just built, so offering
        // it as an icon source (or a watch target) is pointless and just clutters the lists.
        // startsWith: profile packs share the base package with a ".p<id>" suffix.
        iconPacks = appManager.getIconPacks().filter { !it.packageName.startsWith(IconPackBuilder.PACKAGE_NAME) }
        loadAppFilterElements(installedApps)
    }

    private suspend fun loadAppFilterElements(installedApps: List<InstalledApplication>) {
        installedApplications = installedApps
        appFilterElements = loadIsolatedAppFilters(
            iconPacks,
            loader = { pack ->
                appManager.getAppFilterRawElements(pack.packageName, installedApplications)
            },
            onFailure = { pack, error ->
                Log.error("IconPackRepository", "Skipping malformed appfilter: ${pack.packageName}", error)
            }
        )
        appFilterElementsByPackage = appFilterElements.entries.associate { (pack, elements) ->
            pack.packageName to elements
        }
        iconPackLoaded = true
    }

    /** The pack's classic fallback styling (iconback/mask/upon/scale) for unthemed apps. */
    fun getIconPackFallback(iconPack: String): IconPackFallback {
        if (iconPack == "") return IconPackFallback()
        return synchronized(fallbackCache) {
            fallbackCache[iconPack] ?: run {
                val fallback = try {
                    appManager.getIconPackFallback(iconPack)
                } catch (error: Exception) {
                    Log.error("IconPackRepository", "Ignoring malformed fallback: $iconPack", error)
                    IconPackFallback()
                }
                fallbackCache[iconPack] = fallback
                fallback
            }
        }
    }

    /** Drawables every installed app has in [iconPack], keyed by app. */
    fun getAppDrawables(iconPack: String): Map<InstalledApplication, ResourceDrawable> {
        if (iconPack == "") return emptyMap()
        val elements = appFilterElementsByPackage[iconPack] ?: return emptyMap()

        return appManager.getDrawableFromAppFilterElements(
            iconPack,
            installedApplications,
            elements
        )
    }

    private fun getAppDrawable(app: InstalledApplication, iconPack: String): ResourceDrawable? {
        if (iconPack == "") return null
        val elements = appFilterElementsByPackage[iconPack] ?: return null
        return appManager.getDrawableFromAppFilterElements(iconPack, app, elements)
    }

    /** Calendar mappings declared by the currently installed version of [iconPackageName]. */
    internal fun declaredCalendarSelections(iconPackageName: String): List<CalendarSelection> {
        if (iconPackageName.isEmpty()) return emptyList()
        val elements = appFilterElementsByPackage[iconPackageName] ?: return emptyList()
        return appManager.getCalendarApplications(installedApplications, elements).map { (app, prefix) ->
            CalendarSelection(app, iconPackageName, prefix)
        }
    }

    /**
     * Distinct calendar-day prefixes [iconPackageName] declares in its appfilter (`<calendar>`
     * entries), e.g. `{"google_cal_", "samsung_cal_"}`. Used to badge only genuine calendar
     * icons in the browser instead of anything whose name happens to end in a number. Reads
     * the already-parsed [appFilterElements] — no I/O.
     */
    fun calendarPrefixes(iconPackageName: String): Set<String> {
        val elements = appFilterElementsByPackage[iconPackageName] ?: return emptySet()
        return elements.filterIsInstance<RawCalendar>().map { it.prefix }.toSet()
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
        val elements = appFilterElementsByPackage[iconPackageName] ?: return null
        val component = app.toComponentInfo()
        return elements.filterIsInstance<RawCalendar>().find { it.component == component }?.prefix
    }

    /** Builds one collision-free export from both global and per-app calendar selections. */
    internal fun calendarBuildData(selections: List<CalendarSelection>): CalendarBuildData<Drawable> =
        buildCalendarData(selections) { pack, prefix ->
            loadCalendarDays(prefix) { name -> appManager.getDrawableByName(pack, name) }
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
                    val icon = runCatching {
                        appManager.getResIcon(pack.packageName, pack.iconID)
                    }.getOrNull()

                    if (icon != null) {
                        map[pack.packageName] = ResourceDrawable(pack.iconID, icon)
                    }
                } else {
                    val icon = runCatching {
                        getAppDrawable(application, pack.packageName)
                    }.getOrNull()

                    if (icon != null) {
                        map[pack.packageName] = icon
                    }
                }
            }

            map
        }
}
