package dev.renkinProject.renkin.apk

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
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
import java.util.concurrent.atomic.AtomicLong

/** The appfilter elements one load parsed, narrowed to [applications]; compared by identity. */
@Immutable
class AppFilterIndex internal constructor(
    internal val elementsByPackage: Map<String, List<RawElement>> = emptyMap(),
    internal val applications: List<InstalledApplication> = emptyList()
) {
    internal val drawableNamesByPackage: Map<String, Map<String, String>> =
        elementsByPackage.mapValues { (_, elements) -> indexAppDrawableNames(elements) }
}

/** Packs and the index built from them are published together, so no reader can pair them across loads. */
data class IconPackState(
    val packs: List<IconPack> = emptyList(),
    val loaded: Boolean = false,
    internal val index: AppFilterIndex = AppFilterIndex()
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

/**
 * Which Renkin-generated packs are offered as icon sources. The active profile's own pack never
 * is (it only holds the icons being edited); the others only when Settings shows them, or when
 * this profile already selected one, so that selection does not dangle.
 */
data class GeneratedPackVisibility(
    val showAll: Boolean = false,
    val activePack: String = "",
    val selected: Set<String> = emptySet()
) {
    fun isVisible(packageName: String): Boolean =
        packageName != activePack && (showAll || packageName in selected)
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

    private val loadGeneration = AtomicLong()
    @Volatile private var installedGeneratedPacks: Set<String> = emptySet()
    @Volatile private var visibleGeneratedPacks: Set<String> = emptySet()
    private val fallbackCache = mutableMapOf<String, IconPackFallback>()

    private val resourceResolver by lazy { PackageResourceResolver(context) }
    private val appManager: ApplicationManager by lazy { ApplicationManager(context, resourceResolver) }

    /**
     * [installedApps] comes from the caller's already-loaded app list — re-scanning the
     * launcher here used to repeat the whole per-app label/icon work a second time at startup.
     */
    suspend fun load(installedApps: List<InstalledApplication>, visibility: GeneratedPackVisibility = GeneratedPackVisibility()) = load(
        installedApps,
        discover = iconPackCatalog::installedIconPacks,
        visibility = visibility,
        loadElements = { pack, apps -> appManager.getAppFilterRawElements(pack.packageName, apps) }
    )

    internal suspend fun load(
        installedApps: List<InstalledApplication>,
        discover: () -> List<IconPack>,
        visibility: GeneratedPackVisibility = GeneratedPackVisibility(),
        loadElements: (IconPack, List<InstalledApplication>) -> List<RawElement>
    ) = withContext(Dispatchers.Default) {
        val generation = startLoad()
        val all = discover()
        val generated = all.map { it.packageName }.filter(IconPackBuilder::isOwnPack).toSet()
        val discovered = all.filter { it.packageName !in generated || visibility.isVisible(it.packageName) }
        installedGeneratedPacks = generated
        visibleGeneratedPacks = generated.filter(visibility::isVisible).toSet()
        publish(generation, discovered, buildIndex(discovered, installedApps, loadElements))
    }

    /** True when [visibility] would offer other generated packs than the last load did. */
    fun generatedPacksChange(visibility: GeneratedPackVisibility): Boolean =
        installedGeneratedPacks.filter(visibility::isVisible).toSet() != visibleGeneratedPacks

    private fun startLoad(): Long {
        val generation = loadGeneration.incrementAndGet()
        mutableState.update { it.copy(loaded = false) }
        synchronized(fallbackCache) { fallbackCache.clear() }
        return generation
    }

    private suspend fun buildIndex(
        packs: List<IconPack>,
        installedApps: List<InstalledApplication>,
        loadElements: (IconPack, List<InstalledApplication>) -> List<RawElement>
    ): AppFilterIndex {
        val elements = loadIsolatedAppFilters(
            packs,
            loader = { pack -> loadElements(pack, installedApps) },
            onFailure = { pack, error ->
                Log.error("IconPackRepository", "Skipping malformed appfilter: ${pack.packageName}", error)
            }
        )
        return AppFilterIndex(elements.entries.associate { (pack, parsed) -> pack.packageName to parsed }, installedApps)
    }

    // A load that finishes after a newer one started must not overwrite the newer result.
    private fun publish(generation: Long, packs: List<IconPack>, index: AppFilterIndex) {
        var published = false
        mutableState.update { current ->
            published = generation == loadGeneration.get()
            if (published) IconPackState(packs, loaded = true, index = index) else current
        }
        if (published) synchronized(fallbackCache) { fallbackCache.clear() }
    }

    /**
     * Rebuilds only the appfilter indexes for a changed component set, e.g. after the user added
     * an app. Pack discovery stays as it is.
     */
    suspend fun reindex(installedApps: List<InstalledApplication>) = reindex(installedApps) { pack, apps ->
        appManager.getAppFilterRawElements(pack.packageName, apps)
    }

    internal suspend fun reindex(
        installedApps: List<InstalledApplication>,
        loadElements: (IconPack, List<InstalledApplication>) -> List<RawElement>
    ) = withContext(Dispatchers.Default) {
        val generation = startLoad()
        val packs = state.value.packs
        publish(generation, packs, buildIndex(packs, installedApps, loadElements))
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
        state.value.index.drawableNamesByPackage.mapValues { (_, drawables) -> drawables.size }

    fun additionalAppCounts(primaryPackage: String): Map<String, Int> {
        val index = state.value.index
        val primary = index.drawableNamesByPackage[primaryPackage].orEmpty()
        return index.drawableNamesByPackage.mapValues { (_, drawables) ->
            drawables.keys.count { it !in primary }
        }
    }

    internal fun captureAppDrawables(iconPack: String): () -> Map<InstalledApplication, ResourceDrawable> {
        val index = state.value.index
        val elements = index.elementsByPackage[iconPack].orEmpty()
        val applications = index.applications
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
    fun getAppDrawable(app: InstalledApplication, iconPack: String): ResourceDrawable? =
        getAppDrawable(app, iconPack, state.value.index)

    private fun getAppDrawable(app: InstalledApplication, iconPack: String, index: AppFilterIndex): ResourceDrawable? {
        if (iconPack == "") return null
        val drawableName = index.drawableNamesByPackage[iconPack]?.get(app.toComponentInfo())
            ?: return null
        return appManager.getResourceDrawableByName(iconPack, drawableName)
    }

    /** Calendar mappings declared by the currently installed version of [iconPackageName]. */
    internal fun declaredCalendarSelections(iconPackageName: String): List<CalendarSelection> {
        if (iconPackageName.isEmpty()) return emptyList()
        val index = state.value.index
        val elements = index.elementsByPackage[iconPackageName] ?: return emptyList()
        return appManager.getCalendarApplications(index.applications, elements).map { (app, prefix) ->
            CalendarSelection(app, iconPackageName, prefix)
        }
    }

    /**
     * Distinct calendar-day prefixes [iconPackageName] declares in its appfilter (`<calendar>`
     * entries), e.g. `{"google_cal_", "samsung_cal_"}`. Used to badge only genuine calendar
     * icons in the browser instead of anything whose name happens to end in a number. Reads
     * the already-parsed appfilter index — no I/O.
     */
    fun calendarPrefixes(iconPackageName: String): Set<String> {
        val elements = state.value.index.elementsByPackage[iconPackageName] ?: return emptySet()
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
        val res = resourceResolver.getResources(iconPackageName) ?: return false
        fun has(name: String) = resourceResolver.hasDrawable(res, iconPackageName, name)
        return (has(prefix + "1") || has(prefix + "01")) && has(prefix + "28")
    }

    /** Builds one collision-free export from both global and per-app calendar selections. */
    internal fun calendarBuildData(selections: List<CalendarSelection>): CalendarBuildData<Drawable> =
        buildCalendarData(selections) { pack, prefix ->
            val res = resourceResolver.getResources(pack)
            loadCalendarDays(prefix) { name -> res?.let { resourceResolver.getDrawableByName(it, pack, name) } }
        }

    /**
     * Day numbers (1..31) the source pack is missing for [prefix], checking both the plain
     * (`prefix7`) and zero-padded (`prefix07`) naming. Used to warn before a build that a
     * calendar icon won't rotate cleanly because some days fall back to a repeated drawable.
     */
    fun missingCalendarDays(iconPackageName: String, prefix: String): List<Int> {
        if (prefix.isEmpty()) return emptyList()
        val res = resourceResolver.getResources(iconPackageName) ?: return (1..31).toList()
        return (1..31).filter { day ->
            !resourceResolver.hasDrawable(res, iconPackageName, prefix + day) &&
                !resourceResolver.hasDrawable(res, iconPackageName, prefix + day.toString().padStart(2, '0'))
        }
    }

    /** Each pack's own launcher icon, keyed by package, for the icon-pack dropdowns. */
    suspend fun getDropdownIcons(): Map<String, ResourceDrawable> =
        withContext(Dispatchers.Default) {
            state.value.packs.mapNotNull { pack ->
                runCatching { resourceResolver.getDrawable(pack.packageName, pack.iconID) }.getOrNull()
                    ?.let { pack.packageName to ResourceDrawable(pack.iconID, it) }
            }.toMap()
        }
}
