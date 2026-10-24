package dev.renkinProject.renkin.apk

import android.content.Context
import android.graphics.drawable.Drawable
import dev.renkinProject.renkin.data.AppTargetCodec
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.IconPackFallback
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawCalendar
import dev.renkinProject.renkin.data.RawElement
import dev.renkinProject.renkin.data.RawItem
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.IconPackCatalog
import dev.renkinProject.renkin.packages.PackageResourceResolver
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class IconPackState(
    val packs: List<IconPack> = emptyList(),
    val loaded: Boolean = false
)

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

/** A component one or more installed packs theme; the first pack supplies the preview. */
data class PackAppCandidate(
    val packageName: String,
    val activityName: String,
    val iconPack: String,
    val drawableName: String,
    val packCount: Int
) {
    val key: String get() = "$packageName/$activityName"
}

// Android's ICU regex rejects an unescaped closing brace, unlike the JVM.
private val COMPONENT_INFO = Regex("""ComponentInfo\{([^/\{\}\s]+)/([^/\{\}\s]+)\}""")

/** Splits an appfilter `ComponentInfo{package/activity}`; relative activities are expanded. */
internal fun parseComponentInfo(component: String): Pair<String, String>? {
    val match = COMPONENT_INFO.matchEntire(component.trim()) ?: return null
    val packageName = match.groupValues[1]
    val activity = AppTargetCodec.expandActivityName(packageName, match.groupValues[2])
    if (!AppTargetCodec.isValidPackageName(packageName) || !AppTargetCodec.isValidClassName(activity)) return null
    return packageName to activity
}

internal fun indexAppDrawableNames(elements: List<RawElement>): Map<String, String> = buildMap {
    for (element in elements) {
        if (element is RawItem) put(element.component, element.drawableLink)
    }
}

/**
 * Owns everything about installed icon packs: the pack list, their app-filter elements,
 * the per-app drawables resolved from them, and the calendar icons derived from them.
 * [ApplicationProvider] delegates here; icon generation and pack building query it.
 *
 * [state] publishes pack discovery and readiness independently of Compose. UI collectors
 * receive the latest snapshot when they resume; action code reads the current value.
 */
class IconPackRepository(
    private val context: Context,
    private val iconPackCatalog: IconPackCatalog
) {
    private val mutableState = MutableStateFlow(IconPackState())
    val state: StateFlow<IconPackState> = mutableState.asStateFlow()
    /** Action-time snapshot only; UI collects [state] to observe loading and pack changes. */
    val iconPacks: List<IconPack> get() = state.value.packs
    /** Action-time snapshot only; UI collects [state] to observe loading and pack changes. */
    val iconPackLoaded: Boolean get() = state.value.loaded

    private var appFilterElements: Map<IconPack, List<RawElement>> = emptyMap()
    private var appFilterElementsByPackage: Map<String, List<RawElement>> = emptyMap()
    private var appDrawableNamesByPackage: Map<String, Map<String, String>> = emptyMap()
    private var installedApplications: List<InstalledApplication> = listOf()
    private val fallbackCache = mutableMapOf<String, IconPackFallback>()

    private val resourceResolver by lazy { PackageResourceResolver(context) }
    private val appManager: ApplicationManager by lazy { ApplicationManager(context, resourceResolver) }

    /**
     * [installedApps] comes from the caller's already-loaded app list — re-scanning the
     * launcher here used to repeat the whole per-app label/icon work a second time at startup.
     */
    suspend fun load(installedApps: List<InstalledApplication>) = load(
        installedApps,
        discover = iconPackCatalog::installedIconPacks,
        loadElements = { pack, apps -> appManager.getAppFilterRawElements(pack.packageName, apps) }
    )

    internal suspend fun load(
        installedApps: List<InstalledApplication>,
        discover: () -> List<IconPack>,
        loadElements: (IconPack, List<InstalledApplication>) -> List<RawElement>
    ) = withContext(Dispatchers.Default) {
        mutableState.update { it.copy(loaded = false) }
        synchronized(fallbackCache) { fallbackCache.clear() }
        // Drop our own generated pack — it only ever holds icons we just built, so offering
        // it as an icon source (or a watch target) is pointless and just clutters the lists.
        val discovered = discover()
            .filter { !IconPackBuilder.isOwnPack(it.packageName) }
        mutableState.update { it.copy(packs = discovered) }
        loadAppFilterElements(installedApps, loadElements)
    }

    private suspend fun loadAppFilterElements(
        installedApps: List<InstalledApplication>,
        loadElements: (IconPack, List<InstalledApplication>) -> List<RawElement>
    ) {
        installedApplications = installedApps
        appFilterElements = loadIsolatedAppFilters(
            iconPacks,
            loader = { pack -> loadElements(pack, installedApplications) },
            onFailure = { pack, error ->
                Log.error("IconPackRepository", "Skipping malformed appfilter: ${pack.packageName}", error)
            }
        )
        appFilterElementsByPackage = appFilterElements.entries.associate { (pack, elements) ->
            pack.packageName to elements
        }
        appDrawableNamesByPackage = appFilterElementsByPackage.mapValues { (_, elements) ->
            indexAppDrawableNames(elements)
        }
        mutableState.update { it.copy(loaded = true) }
    }

    /**
     * Rebuilds only the appfilter indexes for a changed component set, e.g. after the user added
     * an app. Pack discovery stays as it is.
     */
    suspend fun reindex(installedApps: List<InstalledApplication>) = withContext(Dispatchers.Default) {
        mutableState.update { it.copy(loaded = false) }
        loadAppFilterElements(installedApps) { pack, apps -> appManager.getAppFilterRawElements(pack.packageName, apps) }
    }

    /**
     * Every component any installed pack themes, read on demand for the Add app sheet. This
     * parses each appfilter again without the installed-app filter, so it is never run at startup.
     */
    suspend fun appFilterCandidates(): List<PackAppCandidate> = withContext(Dispatchers.Default) {
        val parsed = loadIsolatedAppFilters(
            iconPacks,
            loader = { pack -> appManager.allAppFilterItems(pack.packageName) },
            onFailure = { pack, error ->
                Log.error("IconPackRepository", "Skipping malformed appfilter: ${pack.packageName}", error)
            }
        )
        val byComponent = linkedMapOf<String, PackAppCandidate>()
        for ((pack, elements) in parsed) {
            for (item in elements.filterIsInstance<RawItem>()) {
                val component = parseComponentInfo(item.component) ?: continue
                val existing = byComponent[item.component]
                byComponent[item.component] = existing?.copy(packCount = existing.packCount + 1)
                    ?: PackAppCandidate(component.first, component.second, pack.packageName, item.drawableLink, 1)
            }
        }
        byComponent.values.toList()
    }

    /** The artwork a pack candidate is shown with in the Add app sheet. */
    fun candidateDrawable(candidate: PackAppCandidate): ResourceDrawable? =
        appManager.getResourceDrawableByName(candidate.iconPack, candidate.drawableName)

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

    /**
     * How many of the user's installed apps each pack declares an icon for, keyed by pack.
     *
     * Read off the index built at load time — `<item>` entries only, already narrowed to
     * installed components and deduplicated per component, so asking does no parsing or drawable
     * decoding. It counts declared mappings, not decodable artwork: a malformed pack can point at
     * a resource that isn't there, and verifying every drawable would mean decoding the whole pack.
     */
    fun matchedAppCounts(): Map<String, Int> =
        appDrawableNamesByPackage.mapValues { (_, drawables) -> drawables.size }

    internal fun captureAppDrawables(iconPack: String): () -> Map<InstalledApplication, ResourceDrawable> {
        val elements = appFilterElementsByPackage[iconPack]?.toList() ?: emptyList()
        val applications = installedApplications.toList()
        return {
            if (iconPack.isEmpty() || elements.isEmpty()) emptyMap()
            else appManager.getDrawableFromAppFilterElements(iconPack, applications, elements)
        }
    }

    /**
     * Resolves one component without scanning or decoding the rest of the pack. The index is
     * rebuilt with the appfilter, and duplicate component declarations keep the last value just
     * like the full-map path.
     */
    fun getAppDrawable(app: InstalledApplication, iconPack: String): ResourceDrawable? {
        if (iconPack == "") return null
        val drawableName = appDrawableNamesByPackage[iconPack]?.get(app.toComponentInfo())
            ?: return null
        return appManager.getResourceDrawableByName(iconPack, drawableName)
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
        val hasFirst = resourceResolver.hasDrawable(iconPackageName, prefix + "1") ||
            resourceResolver.hasDrawable(iconPackageName, prefix + "01")
        return hasFirst && resourceResolver.hasDrawable(iconPackageName, prefix + "28")
    }

    /** Builds one collision-free export from both global and per-app calendar selections. */
    internal fun calendarBuildData(selections: List<CalendarSelection>): CalendarBuildData<Drawable> =
        buildCalendarData(selections) { pack, prefix ->
            loadCalendarDays(prefix) { name -> resourceResolver.getDrawableByName(pack, name) }
        }

    /**
     * Day numbers (1..31) the source pack is missing for [prefix], checking both the plain
     * (`prefix7`) and zero-padded (`prefix07`) naming. Used to warn before a build that a
     * calendar icon won't rotate cleanly because some days fall back to a repeated drawable.
     */
    fun missingCalendarDays(iconPackageName: String, prefix: String): List<Int> {
        if (prefix.isEmpty()) return emptyList()
        return (1..31).filter { day ->
            !resourceResolver.hasDrawable(iconPackageName, prefix + day) &&
                !resourceResolver.hasDrawable(iconPackageName, prefix + day.toString().padStart(2, '0'))
        }
    }

    /** One representative icon per pack (the pack's own icon, or the app's icon in that pack). */
    suspend fun getDropdownIcons(application: InstalledApplication?): Map<String, ResourceDrawable> =
        withContext(Dispatchers.Default) {
            val map = mutableMapOf<String, ResourceDrawable>()

            for (pack in iconPacks) {
                if (application == null) {
                    val icon = runCatching {
                        resourceResolver.getDrawable(pack.packageName, pack.iconID)
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
