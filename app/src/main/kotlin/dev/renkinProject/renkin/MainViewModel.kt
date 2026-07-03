package dev.renkinProject.renkin

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.renkinProject.renkin.apk.ApkUninstaller
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.data.BuiltPrimaryIconPackKey
import dev.renkinProject.renkin.data.BuiltPrimarySourceKey
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.PrimaryIconPackKey
import dev.renkinProject.renkin.data.PrimarySourceKey
import dev.renkinProject.renkin.data.SOURCE_DEFAULT
import dev.renkinProject.renkin.data.getEnumValue
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.data.isSystemInDarkTheme
import dev.renkinProject.renkin.data.setEnumValue
import dev.renkinProject.renkin.data.setStringValue
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.extension.normalizeIconSearchQuery
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconSortOrder
import dev.renkinProject.renkin.icon.creator.PACK_DETAIL_LIMIT
import dev.renkinProject.renkin.icon.creator.PACK_ROW_LIMIT
import dev.renkinProject.renkin.icon.creator.PackIconPreview
import dev.renkinProject.renkin.icon.creator.PackRowPreviews
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Owns the [ApplicationProvider] for the app's lifetime. The provider is injected (a Hilt
 * @Singleton), so the loaded app list / icon packs survive configuration changes such as
 * rotation instead of being re-loaded on every Activity recreation.
 */
/**
 * The icon-building operations the per-app options dialog's `IconDraftState` needs.
 * [MainViewModel] implements it; a test can supply a fake, so the draft/generation logic is
 * unit-testable without a real view model or Android.
 */
interface IconPreviewBuilder {
    suspend fun previewIcon(
        app: PackageInfoStruct,
        options: GenerationOptions,
        customIcon: ResourceDrawable?
    ): IconPackDrawable?

    suspend fun applyModifier(icon: IconPackDrawable, options: GenerationOptions): IconPackDrawable
}

// Roughly 30 preview bitmaps per row at ~96px ≈ 1 MB; 24 rows caps the browser cache near 25 MB.
private const val PACK_ROW_PREVIEW_CACHE_MAX = 24

// Previews only ever render at ~56-64dp; a 96px bitmap covers that on the highest densities while
// keeping the full-pack grid (up to PACK_DETAIL_LIMIT items) within a sane memory budget — a full
// 256px raster each would be ~100 MB for 400 icons.
private const val PREVIEW_PX = 96

private fun Bitmap.scaledPreview(max: Int = PREVIEW_PX): Bitmap {
    val biggest = maxOf(width, height)
    if (biggest <= max) return this
    val scale = max.toFloat() / biggest
    return Bitmap.createScaledBitmap(
        this,
        (width * scale).toInt().coerceAtLeast(1),
        (height * scale).toInt().coerceAtLeast(1),
        true
    )
}

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val appProvider: ApplicationProvider
) : AndroidViewModel(application), IconPreviewBuilder {

    // One shared manager for the pack-preview lookups, instead of a fresh instance per call.
    private val appMan by lazy { ApplicationManager(getApplication()) }

    // ---- Model state exposed to the UI (read-only) -------------------------------
    // The UI observes these instead of reaching through to ApplicationProvider, so the
    // view model stays the single point of contact with the model layer. Each is backed
    // by Compose state in the provider/repository, so reads in composition stay reactive.

    /** The loaded apps, each with its current (created) icon. Edited via [applyIcon]. */
    val applicationList: List<PackageInfoStruct> get() = appProvider.applicationList

    /** The installed icon packs available as icon sources. */
    val iconPacks: List<IconPack> get() = appProvider.iconPacks

    /** True once the icon packs have finished loading. */
    val iconPackLoaded: Boolean get() = appProvider.iconPackLoaded

    /** True once the app list has finished loading. */
    val applicationsLoaded: Boolean get() = appProvider.applicationsLoaded

    // Keys ("package/activity") of the apps already in the last built/saved pack.
    // An app with an icon whose key is NOT here is "added" (pending build); a key here
    // whose app no longer has an icon is "removed". Reloaded after each successful build,
    // so the change state is a diff against what was actually built (survives refresh).
    var builtKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    // Keys whose icons were manually changed this session (via applyIcon with a non-null icon).
    // Distinct from builtKeys: an app in both sets had an icon already and was re-edited.
    // Reset after every successful build.
    var updatedKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    // Set when opened from an icon-watch notification; the home screen shows the apply
    // modal for this suggestion.
    var pendingWatchSuggestionId by mutableStateOf<Long?>(null)
        private set

    fun setPendingWatchSuggestion(id: Long) { pendingWatchSuggestionId = id }

    /**
     * Opens a watch suggestion from its notification: switches to the profile that owns it
     * first (auto-saving the active profile's unsaved work — the user opted into that), then
     * shows the apply modal. Waits out a cold start so the switch doesn't race initialization.
     */
    fun openSuggestionInProfile(suggestionId: Long, profileId: Long) {
        viewModelScope.launch {
            // Cold start from a notification: the app list / saved icons may still be loading.
            withTimeoutOrNull(10_000) {
                while (!appProvider.startupComplete) delay(50)
            }
            if (profileId > 0 && profileId != appProvider.activeProfileId && appProvider.startupComplete) {
                performSwitch(profileId, saveFirst = hasUnsavedChanges())
            }
            pendingWatchSuggestionId = suggestionId
        }
    }

    fun clearPendingWatchSuggestion() { pendingWatchSuggestionId = null }

    // Label of an external icon pack installed while the app was open; non-null drives a
    // dialog prompting to reload so the new pack appears among the available sources.
    var newIconPackInstalled by mutableStateOf<String?>(null)
        private set

    // Packs we've already prompted for this session, so dismissing the dialog doesn't make
    // it pop again on every return to the foreground.
    private val promptedIconPacks = mutableSetOf<String>()

    /** Prompts for a newly-detected icon pack, at most once per pack per session. */
    fun onIconPackInstalled(packageName: String, label: String) {
        if (!promptedIconPacks.add(packageName)) return
        newIconPackInstalled = label
    }

    fun dismissNewIconPack() { newIconPackInstalled = null }

    // One-shot toast events (string resource ids). Emitted when an operation finishes and
    // collected once near the composition root, which forwards them to the shared Toaster.
    // Replaces the old mirrored buildInstalled/syncDone/appsRefreshed flag + consume pairs.
    private val _toastEvents = Channel<Int>(Channel.BUFFERED)
    val toastEvents = _toastEvents.receiveAsFlow()

    init {
        appProvider.defaultColor =
            if (application.isSystemInDarkTheme()) Color.White else Color.Black

        // Loaded once. The Renkin pack reads the app list, so it runs after the
        // apps are loaded; icon packs are independent and load in parallel. The heavy
        // work hops to Dispatchers.Default inside each call, so viewModelScope (main)
        // is fine here.
        viewModelScope.launch {
            appProvider.initializeApplications()
            appProvider.initializeRenkinPack()
            builtKeys = appProvider.getSavedPackKeys()
        }
        viewModelScope.launch { appProvider.initializeIconPacks() }
    }

    // ---- Operation orchestration -------------------------------------------------
    // The heavy work lives in suspend functions on ApplicationProvider (each hops to
    // Dispatchers.Default internally), so these run safely on viewModelScope (main).
    // UI state is exposed as Compose state; composables read it and call these instead
    // of spinning up their own lifecycleScope coroutines.

    /** True while [refresh] is regenerating icons. Drives the refresh spinner and blocks build. */
    var isRefreshing by mutableStateOf(false)
        private set

    /**
     * Regenerates every app's icon from the current preferences. Returns false (without
     * starting) when an icon pack is configured but not yet loaded, so the caller can warn.
     */
    fun refresh(preferences: Preferences): Boolean {
        val iconPackageName = preferences.getStringValue(PrimaryIconPackKey)
        if (!appProvider.iconPackLoaded && iconPackageName != "") return false
        if (isRefreshing) return true

        viewModelScope.launch {
            isRefreshing = true
            try {
                appProvider.refreshIcons(preferences)
            } finally {
                // Without this a failed refresh would leave the spinner on forever and block builds.
                isRefreshing = false
            }
        }
        return true
    }

    /** Current build step text while a pack is building; null when no build is in progress. */
    var buildStep by mutableStateOf<String?>(null)
        private set

    /**
     * (done, total) while the builder writes the per-app icons — the long phase — so the build
     * dialog can show a determinate bar; null during the other (indeterminate) steps.
     */
    var buildProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /**
     * First-install timestamps (epoch millis) for [packageNames] — the data behind the
     * "recently installed" sort. Looked up off the main thread; a package that's gone yields 0.
     * The UI reads these here instead of touching PackageManager itself.
     */
    suspend fun installTimes(packageNames: List<String>): Map<String, Long> =
        withContext(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            packageNames.associateWith { pkg ->
                runCatching { pm.getPackageInfo(pkg, 0).firstInstallTime }.getOrDefault(0L)
            }
        }

    /** True if the active profile's generated icon pack is currently installed on the device. */
    private suspend fun isIconPackInstalled(): Boolean = runCatching {
        getApplication<Application>().packageManager.getPackageInfo(appProvider.activePackPackageName(), 0)
    }.isSuccess

    /**
     * Shown as a dialog after a successful build+install: FIRST_INSTALL tells the user to pick
     * "Renkin Pack" in the launcher, UPDATE explains the switch-away-and-back launcher refresh.
     * null = no dialog pending.
     */
    enum class BuildOutcome { FIRST_INSTALL, UPDATE }
    /** Outcome + the launcher label of the pack that was just built, for the dialog text. */
    data class BuildOutcomeInfo(val outcome: BuildOutcome, val packLabel: String)
    var buildOutcome by mutableStateOf<BuildOutcomeInfo?>(null)
        private set

    fun dismissBuildOutcome() { buildOutcome = null }

    /** Builds, signs and installs the icon pack, surfacing progress through [buildStep]. */
    fun build(preferences: Preferences) {
        if (buildStep != null) return
        viewModelScope.launch {
            try {
                buildStep = ""
                val pack = appProvider.buildAndSignIconPack(
                    preferences,
                    // A new step ends the per-app phase, so the bar goes back to indeterminate.
                    textMethod = { buildStep = it; buildProgress = null },
                    progressMethod = { done, total -> buildProgress = done to total }
                )
                // The system install is the slow part (2-3s) — show it as its own step so the
                // dialog reflects what's happening. "Updating" when our pack is already
                // installed, "Installing" for a first build. Decided before installing, so it
                // also tells us which follow-up instructions dialog to show afterwards.
                val wasUpdate = isIconPackInstalled()
                buildStep = getApplication<Application>().getString(
                    if (wasUpdate) R.string.buildUpdating else R.string.buildInstalling
                )
                if (appProvider.installIconPack(pack)) {
                    // The next-steps dialog replaces the old "installed!" toast: what to do in
                    // the launcher differs between a first install and an update.
                    val label = appProvider.activeProfile()?.let { it.packLabel.ifEmpty { it.name } } ?: "Renkin Pack"
                    buildOutcome = BuildOutcomeInfo(
                        if (wasUpdate) BuildOutcome.UPDATE else BuildOutcome.FIRST_INSTALL,
                        label
                    )
                    // The saved pack now matches the current icons → reset both change baselines.
                    builtKeys = appProvider.getSavedPackKeys()
                    updatedKeys = emptySet()
                    // The hero pick only sticks once built: record what was built so startup
                    // can restore it over any later, unbuilt pick.
                    val store = getApplication<Application>().dataStore
                    store.setEnumValue(BuiltPrimarySourceKey, preferences.getEnumValue(PrimarySourceKey, SOURCE_DEFAULT))
                    store.setStringValue(BuiltPrimaryIconPackKey, preferences.getStringValue(PrimaryIconPackKey))
                } else {
                    // install returned false: it failed or the user cancelled the system installer.
                    _toastEvents.trySend(R.string.iconPackInstallFailed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A build/sign failure must surface and still release the UI, not leave the
                // (non-dismissable) progress dialog stuck on screen.
                Log.error("MainViewModel", "Icon pack build failed", e)
                _toastEvents.trySend(R.string.iconPackBuildFailed)
            } finally {
                buildStep = null
                buildProgress = null
            }
        }
    }

    // Records that [app] was hand-edited this session (so the build preview marks it "changed").
    // Clearing an icon (icon == null) is a removal, not a change, so it isn't recorded.
    private fun markUpdated(app: PackageInfoStruct, icon: IconPackDrawable?) {
        if (icon != null) updatedKeys = updatedKeys + app.key
    }

    /**
     * Assigns (or clears, when [icon] is null) the created icon for the app at [index].
     * [sourcePackName] is the pack the icon was taken from (null/empty when not from a pack).
     */
    fun applyIcon(index: Int, app: PackageInfoStruct, icon: IconPackDrawable?, sourcePackName: String? = null) {
        // Hand-picked icons are locked immediately: a refresh never replaces them.
        appProvider.editApplication(index, app.changeExport(icon, sourcePackName = sourcePackName, isRefreshMade = false))
        markUpdated(app, icon)
    }

    /**
     * Applies the icon and the calendar selection together. The edit dialog tracks the
     * calendar prefix/source-pack in local state as the user browses; persisting only the
     * icon (via [applyIcon]) would leave the stored `<calendar>` prefix pointing at the
     * previously chosen pack, so launchers that prefer `<calendar>` would keep showing the
     * old icon. Setting both in one edit keeps the static `<item>` and `<calendar>` in sync.
     */
    fun applyIcon(
        index: Int,
        app: PackageInfoStruct,
        icon: IconPackDrawable?,
        calendarEnabled: Boolean,
        calendarPrefix: String?,
        calendarPackName: String?,
        sourcePackName: String?
    ) {
        appProvider.editApplication(
            index,
            app.changeExport(icon, sourcePackName = sourcePackName, isRefreshMade = false).changeCalendar(calendarEnabled, calendarPrefix, calendarPackName)
        )
        markUpdated(app, icon)
    }

    /** Live count of icons taken from each pack — orders the per-app icon picker. */
    fun packUsageCounts(): Map<String, Int> = appProvider.packUsageCounts()

    /**
     * Toggles the calendar-day-icons flag for [app]. [calendarPrefix] is the drawable-name
     * prefix derived from the icon the user selected (e.g. `"google_cal_"`). Committed
     * immediately (independent of the edit dialog's Confirm).
     */
    fun setCalendarEnabled(app: PackageInfoStruct, enabled: Boolean, calendarPrefix: String?, calendarPackName: String?) {
        appProvider.setCalendar(app, enabled, calendarPrefix, calendarPackName)
    }

    /**
     * Uninstalls the app's own generated icon pack. Emits a toast event for the outcome
     * (uninstalled / not installed); the system shows its own uninstall confirmation.
     */
    fun deleteIconPack() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            if (!isIconPackInstalled()) {
                _toastEvents.trySend(R.string.iconPackNotInstalled)
                return@launch
            }
            if (ApkUninstaller(context).uninstall(appProvider.activePackPackageName())) {
                _toastEvents.trySend(R.string.iconPackUninstalled)
            }
        }
    }

    /** Re-reads the installed icon packs. */
    fun sync() {
        viewModelScope.launch {
            appProvider.forceSync()
            _toastEvents.trySend(R.string.packsSynced)
        }
    }

    /** Reloads apps, icon packs and the saved Renkin pack from scratch. */
    fun refreshApps() {
        viewModelScope.launch {
            appProvider.initialize()
            _toastEvents.trySend(R.string.appListRefreshed)
        }
    }

    // ---- Icon preview (used by the per-app options / watch-apply dialogs) ---------
    // Pure builders with no side effects: they return an icon for the dialog to preview;
    // committing it goes through applyIcon. Each hops to Dispatchers.Default internally.

    /** Builds a preview icon for [app] from [options] (optionally a specific pack pick). */
    override suspend fun previewIcon(
        app: PackageInfoStruct,
        options: GenerationOptions,
        customIcon: ResourceDrawable?
    ): IconPackDrawable? = appProvider.getIcon(app, options, customIcon)

    /** Applies the modifier from [options] to an already-built icon (e.g. a hand-edited vector). */
    override suspend fun applyModifier(icon: IconPackDrawable, options: GenerationOptions): IconPackDrawable =
        appProvider.applyModifier(icon, options)

    /** Builds the icon a specific pack provides for [app] by drawable name (watch-apply modal). */
    suspend fun iconFromPack(
        app: PackageInfoStruct,
        packPackage: String,
        drawableName: String,
        options: GenerationOptions
    ): IconPackDrawable? = appProvider.getIconFromPackDrawable(app, packPackage, drawableName, options)

    /** The selectable drawables for the icon-pack dropdown, keyed by display name. */
    suspend fun iconPackDropdownIcons(application: InstalledApplication?): Map<String, ResourceDrawable> =
        appProvider.getIconPackDropdownIcons(application)

    /** Builds a pack's icons for the given [drawables] — used by the icon-pack browser previews. */
    suspend fun iconPackIcons(
        iconPackName: String,
        options: GenerationOptions,
        drawables: List<ResourceDrawable>
    ): Map<ResourceDrawable, IconPackDrawable?> =
        appProvider.getIconPackIcons(iconPackName, options, drawables)

    // ---- Icon-pack browser previews ---------------------------------------------------
    // Enumerating a pack's drawables and rasterising their previews is the icon-pack browser's
    // heavy work. It lives here (not in the composables) so the UI only talks to the view model,
    // and so the row cache below can sit next to it.

    // Generating a pack row's preview bitmaps is expensive. Without a cache each row regenerated
    // them every time it scrolled back into view (a LazyColumn discards off-screen items, taking
    // their remembered state with them) — that's the loading flicker seen while scrolling the
    // multi-pack browser. The cache lives on the view model so it also survives reopening the
    // dialog and rotation. Keyed by pack + sort + query + options so a different filter/option set
    // gets a fresh entry; an LRU bound keeps memory sane. Touched only from the main thread.
    private val packRowPreviewCache =
        object : LinkedHashMap<String, PackRowPreviews>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PackRowPreviews>?) =
                size > PACK_ROW_PREVIEW_CACHE_MAX
        }

    private fun packRowCacheKey(
        packageName: String,
        sortOrder: IconSortOrder,
        query: String,
        options: GenerationOptions
    ) = "$packageName|$sortOrder|$query|${options.hashCode()}"

    /**
     * The collapsed row previews for [packageName] (first [PACK_ROW_LIMIT] matches plus the
     * "+N more" count). Returns the cached result instantly when present, otherwise generates
     * and caches it. A malformed pack yields an empty row instead of crashing the browser.
     */
    suspend fun packRowPreviews(
        packageName: String,
        sortOrder: IconSortOrder,
        query: String,
        options: GenerationOptions
    ): PackRowPreviews {
        val key = packRowCacheKey(packageName, sortOrder, query, options)
        packRowPreviewCache[key]?.let { return it }

        val result = withContext(Dispatchers.Default) {
            try {
                val sortedNames = filteredSortedPackNames(appMan, packageName, query, sortOrder)
                val more = (sortedNames.size - PACK_ROW_LIMIT).coerceAtLeast(0)
                val pairs = loadPackIconPairs(appMan, packageName, options, sortedNames.take(PACK_ROW_LIMIT))
                PackRowPreviews(pairs, more)
            } catch (_: Exception) {
                PackRowPreviews(emptyList(), 0)
            }
        }
        packRowPreviewCache[key] = result
        return result
    }

    /**
     * Generates the full-pack grid previews (up to [PACK_DETAIL_LIMIT]) for [packageName],
     * streaming them to [onChunk] a chunk at a time so the grid fills progressively instead of
     * blocking. [onChunk] is invoked on the calling (main) thread. Not cached — the detail grid
     * stays in composition while open, so it only loads once anyway.
     */
    suspend fun packDetailPreviews(
        packageName: String,
        sortOrder: IconSortOrder,
        query: String,
        options: GenerationOptions,
        onChunk: (List<PackIconPreview>) -> Unit
    ) {
        val sortedNames = withContext(Dispatchers.Default) {
            try {
                filteredSortedPackNames(appMan, packageName, query, sortOrder)
            } catch (_: Exception) {
                emptyList()
            }
        }
        for (chunk in sortedNames.take(PACK_DETAIL_LIMIT).chunked(40)) {
            coroutineContext.ensureActive()
            val pairs = withContext(Dispatchers.Default) {
                try {
                    loadPackIconPairs(appMan, packageName, options, chunk)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A malformed icon pack must not crash the browser
                    emptyList()
                }
            }
            onChunk(pairs)
        }
    }

    /** Drawable names of [packageName] matching [query], sorted by [sortOrder]. */
    private fun filteredSortedPackNames(
        appMan: ApplicationManager,
        packageName: String,
        query: String,
        sortOrder: IconSortOrder
    ): List<String> {
        val allNames = appMan.getIconPackDrawableNames(packageName)
        val formattedQuery = query.normalizeIconSearchQuery()
        val matching = if (formattedQuery.isEmpty()) {
            allNames
        } else {
            allNames.filter { it.contains(formattedQuery) }
        }
        return when (sortOrder) {
            IconSortOrder.NAME_ASC -> matching.sortedBy { it }
            IconSortOrder.NAME_DESC -> matching.sortedByDescending { it }
        }
    }

    /** Builds + rasterises the preview icons for the given drawable [names] of a pack. */
    private suspend fun loadPackIconPairs(
        appMan: ApplicationManager,
        packageName: String,
        options: GenerationOptions,
        names: List<String>
    ): List<PackIconPreview> {
        val ids = appMan.getIconPackDrawableIds(packageName, names)
        // names and ids are parallel lists (same order) — build id→name reverse lookup.
        val idToName = names.zip(ids).associate { (name, id) -> id to name }
        val drawables = appMan.getIconPackDrawables(packageName, ids)
        val exportDrawables = iconPackIcons(packageName, options, drawables)
        return exportDrawables.entries
            .filter { it.value != null }
            .distinctBy { it.key.resourceId }
            .map { PackIconPreview(it.key, it.value!!, it.value!!.toBitmap().scaledPreview().asImageBitmap(), idToName[it.key.resourceId] ?: "") }
    }

    /** Clears only the unsaved bulk-refresh icons — see ApplicationProvider.clearRefreshedIcons. */
    fun clearRefreshedIcons() = appProvider.clearRefreshedIcons()

    // ---- Profiles -----------------------------------------------------------------

    /** All profiles for the switcher (default first). */
    val profiles = appProvider.profilesFlow()

    val activeProfileId: Long get() = appProvider.activeProfileId

    /**
     * True when the active profile has changes its saved set doesn't: unsaved refresh output,
     * hand edits from this session, or removals against the saved pack. Drives the
     * save-before-switch prompt.
     */
    fun hasUnsavedChanges(): Boolean {
        val apps = appProvider.applicationList
        if (updatedKeys.isNotEmpty()) return true
        if (apps.any { it.isRefreshMade }) return true
        val iconKeys = apps.filter { it.createdIcon != null }.map { it.key }.toSet()
        return builtKeys.any { it !in iconKeys }
    }

    /**
     * Re-reads the change baselines from the (just switched or just saved) profile's stored
     * pack — every operation that changes which saved set is current ends with this.
     */
    private suspend fun resetChangeBaselines() {
        builtKeys = appProvider.getSavedPackKeys()
        updatedKeys = emptySet()
    }

    /** Saves the active profile if asked, switches to [id], and refreshes the baselines. */
    private suspend fun performSwitch(id: Long, saveFirst: Boolean) {
        if (saveFirst) appProvider.saveActiveProfileIcons()
        appProvider.switchProfile(id)
        resetChangeBaselines()
    }

    /** Switches the active profile (prefs snapshot + icon set swap), optionally saving first. */
    fun switchProfile(id: Long, saveFirst: Boolean = false) {
        viewModelScope.launch { performSwitch(id, saveFirst) }
    }

    /** Creates a profile and switches straight to it, optionally saving the current one first. */
    fun createProfile(name: String, description: String, packLabel: String, saveFirst: Boolean = false) {
        viewModelScope.launch {
            performSwitch(appProvider.createProfile(name, description, packLabel), saveFirst)
        }
    }

    /** Deletes a profile (never the default); switches to the default first when active. */
    fun deleteProfile(id: Long) {
        viewModelScope.launch {
            appProvider.deleteProfile(id)
            resetChangeBaselines()
        }
    }

    /** Clears every created icon (and persists the empty state). */
    fun clearIcons() {
        viewModelScope.launch {
            appProvider.clearIcons()
            // Saved pack is now empty → reset both change baselines.
            resetChangeBaselines()
        }
    }

    /** Calendar-enabled apps whose source pack is missing day drawables (shown before a build). */
    suspend fun calendarWarnings(preferences: Preferences): List<ApplicationProvider.CalendarWarning> =
        appProvider.calendarWarnings(preferences)

    /** Of [prefixes], those that are genuine calendar day-rotation sets in [packPackageName]. */
    suspend fun calendarPrefixesAmong(packPackageName: String, prefixes: List<String>): Set<String> =
        appProvider.calendarPrefixesAmong(packPackageName, prefixes)

    /** Sample icons showing the fallback styling for [fallbackSource], for the Options preview. */
    suspend fun fallbackPreview(preferences: Preferences, fallbackSource: dev.renkinProject.renkin.data.FallbackSource) =
        appProvider.fallbackPreview(preferences, fallbackSource)
}
