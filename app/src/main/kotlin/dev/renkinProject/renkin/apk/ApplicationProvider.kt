package dev.renkinProject.renkin.apk

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.ActiveProfileIdKey
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.data.CalendarIconsKey
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.ExportThemedKey
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.PrimaryIconPackKey
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.restoreProfilePrefs
import dev.renkinProject.renkin.data.snapshotProfilePrefs
import dev.renkinProject.renkin.data.FallbackSource
import dev.renkinProject.renkin.data.SecondaryIconPackKey
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.getDefaultBackgroundColor
import dev.renkinProject.renkin.data.getDefaultIconColor
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.extension.toHexString
import dev.renkinProject.renkin.packages.supportDynamicColors
import dev.renkinProject.renkin.dataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class ApplicationProvider(private val context: Context) {
    // A SnapshotStateList (not mutableStateOf(List)) so editing one app's icon is an O(1)
    // in-place set instead of copying the whole list — refreshIcons edits every app, so the
    // old copy-per-edit made it O(n²). Exposed read-only as List; the UI still observes it.
    private val _applicationList = mutableStateListOf<PackageInfoStruct>()
    val applicationList: List<PackageInfoStruct> get() = _applicationList
    // Icon-pack data (packs, app-filter elements, calendar icons) lives in
    // IconPackRepository; iconPacks / iconPackLoaded delegate to it so existing UI reads
    // stay reactive (the repo backs them with Compose state).
    val iconPacks: List<IconPack> get() = iconPackRepo.iconPacks
    val iconPackLoaded: Boolean get() = iconPackRepo.iconPackLoaded
    var applicationsLoaded: Boolean by mutableStateOf(false)
        private set
    /** True once apps, packs and the saved icons have all loaded (profile switches are safe). */
    var startupComplete: Boolean by mutableStateOf(false)
        private set

    var defaultColor: Color = Color.Unspecified

    private val renkinPackStore = RenkinPackStore(context)
    private val packRepo = RenkinPackRepository(context)
    private val iconPackRepo = IconPackRepository(context)

    /** The profile whose icons/preferences are active. Set before the saved pack loads. */
    var activeProfileId: Long by mutableStateOf(DEFAULT_PROFILE_ID)
        private set
    private val iconGenService = IconGenerationService(context, iconPackRepo)

    private val appManager: ApplicationManager by lazy { ApplicationManager(context) }

    suspend fun initialize() {
        initializeApplications()
        initializeIconPacks()
        initializeRenkinPack()
    }

    suspend fun initializeApplications() = withContext(Dispatchers.Default) {
        val apps = appManager.getAllInstalledApps()
        apps.sort()

        _applicationList.clear()
        _applicationList.addAll(apps.asList())
        applicationsLoaded = true
    }

    suspend fun initializeIconPacks() = iconPackRepo.load()

    suspend fun initializeRenkinPack() {
        activeProfileId = context.dataStore.data.first()[ActiveProfileIdKey] ?: DEFAULT_PROFILE_ID
        loadRenkinPack()
        // MainViewModel's startup calls the initialize* steps individually (not initialize()),
        // and this one runs last of the two that profile switching depends on (apps + saved
        // icons) — so THIS is where switching becomes safe, not initialize().
        startupComplete = true
    }

    suspend fun retrieveOtherIcons(preferences: Preferences) = withContext(Dispatchers.Default) {
        val iconPackageName = preferences.getStringValue(PrimaryIconPackKey)
        val retrieveCalendarIcon = preferences.getBooleanValue(CalendarIconsKey)

        if (iconPackageName != "" && retrieveCalendarIcon) {
            iconPackRepo.retrieveCalendarIcons(iconPackageName)
        }
    }

    suspend fun refreshIcon(application: PackageInfoStruct, preferences: Preferences) = withContext(Dispatchers.Default) {
        // A newly installed app always gets its icon (re)generated
        val genOptions = GenerationOptions.fromPreferences(preferences, context, override = true)
        iconGenService.refreshIcon(application, genOptions) { app, icon, sourcePack ->
            editApplication(app, app.changeExport(icon, sourcePackName = sourcePack, isRefreshMade = true))
        }
    }

    suspend fun refreshIcons(preferences: Preferences) = withContext(Dispatchers.Default) {
        var opt = GenerationOptions.fromPreferences(preferences, context)
        val retrieveCalendarIcon = preferences.getBooleanValue(CalendarIconsKey)

        if (opt.primaryIconPack != "" && retrieveCalendarIcon) {
            iconPackRepo.retrieveCalendarIcons(opt.primaryIconPack)
        }

        // Themed icons on Android 12+ are recoloured with the system dynamic palette
        if (opt.themed && supportDynamicColors()) {
            opt = opt.copy(
                color = context.resources.getColor(R.color.icon_color, null),
                bgColor = context.resources.getColor(R.color.icon_background_color, null)
            )
        }

        // Iterate a snapshot copy: the callback edits the live list in place, and iterating
        // the SnapshotStateList itself while mutating it would throw.
        iconGenService.refreshIcons(applicationList.toList(), opt) { application, icon, isFallback, sourcePack ->
            editApplication(application, application.changeExport(icon, isFallback, sourcePack, isRefreshMade = true))
        }
    }

    suspend fun getIcon(application: PackageInfoStruct, options: GenerationOptions, customIcon: ResourceDrawable? = null): IconPackDrawable? =
        iconGenService.getIcon(application, options, customIcon)

    /**
     * Builds the icon a specific pack provides for an app, by drawable name — used by the
     * icon-watch apply modal to preview/apply a suggested icon. No extra modifier is applied.
     */
    suspend fun getIconFromPackDrawable(
        application: PackageInfoStruct,
        packPackage: String,
        drawableName: String,
        options: GenerationOptions
    ): IconPackDrawable? =
        iconGenService.getIconFromPackDrawable(application, packPackage, drawableName, options)

    /** Applies the modifier from [options] to an already-built icon (e.g. a hand-edited vector). */
    suspend fun applyModifier(icon: IconPackDrawable, options: GenerationOptions): IconPackDrawable =
        iconGenService.applyModifier(icon, options)

    suspend fun buildAndSignIconPack(
        preferences: Preferences,
        textMethod: (text: String) -> Unit,
        progressMethod: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): BuiltIconPack =
        withContext(Dispatchers.Default) {
            val themed = preferences.getBooleanValue(ExportThemedKey)
            val iconColor = preferences.getDefaultIconColor(context)
            val bgColor = preferences.getDefaultBackgroundColor(context)
            val primaryPackName = preferences.getStringValue(PrimaryIconPackKey)

            // Per-app calendar: group opted-in apps by which pack they chose their icon from,
            // then load the day-1..31 drawables from THAT pack (not the global primary).
            // Per-app entries override global calendar for the same app.
            var perAppIcons = emptyMap<InstalledApplication, String>()
            var perAppDrawables = emptyMap<String, android.graphics.drawable.Drawable>()

            applicationList
                .filter { it.hasCalendarIcon }
                .groupBy { it.calendarSourcePack(primaryPackName) }
                .filter { (packName, _) -> packName.isNotEmpty() }
                .forEach { (packName, apps) ->
                    val appsWithPrefixes = apps.map { it.toInstalledApplication() to it.calendarPrefix!! }
                    val (icons, drawables) = iconPackRepo.calendarDataForPrefixes(packName, appsWithPrefixes)
                    perAppIcons = perAppIcons + icons
                    perAppDrawables = perAppDrawables + drawables
                }

            // Global calendar for apps that did NOT opt in per-app
            val perAppPackages = perAppIcons.keys.map { it.packageName }.toSet()
            val allCalendarIcons = iconPackRepo.calendarIcon.filter { it.key.packageName !in perAppPackages } + perAppIcons
            val allCalendarDrawables = iconPackRepo.calendarIconsDrawable + perAppDrawables

            // Each profile builds its own pack: a per-profile package name (side-by-side
            // installs) and the user's chosen launcher label.
            val profile = packRepo.profile(activeProfileId)
            val iconPackGenerator = IconPackBuilder(
                context,
                applicationList,
                allCalendarIcons,
                allCalendarDrawables,
                packPackageName = packPackageNameFor(activeProfileId),
                packLabel = profile?.packLabel?.ifEmpty { profile.name } ?: "Renkin Pack"
            )
            val canBeInstalled = iconPackGenerator.canBeInstalled() // must be called before build and sign
            val apk = iconPackGenerator.buildAndSign(themed, iconColor.toHexString(), bgColor.toHexString(), textMethod, progressMethod)

            BuiltIconPack(apk, iconPackGenerator.getIconPackName(), canBeInstalled)
        }

    suspend fun installIconPack(iconPack: BuiltIconPack): Boolean = withContext(Dispatchers.Default) {
        var success = false

        if (iconPack.canBeInstalled) {
            success = ApkInstaller(context).install(iconPack.uri)
        } else {
            if (ApkUninstaller(context).uninstall(iconPack.packageName)) {
                success = ApkInstaller(context).install(iconPack.uri)
            }
        }

        // A successful build IS the pack — the save matches it. A failed/cancelled install
        // leaves the save marked as not yet built.
        persistActiveProfileIcons(unbuiltAfter = !success)

        success
    }

    /**
     * Persists the active profile's icons without building (offered before switching away).
     * Locks the refresh output like a build does and marks the profile as saved-but-not-built.
     */
    suspend fun saveActiveProfileIcons() = persistActiveProfileIcons(unbuiltAfter = true)

    /**
     * Saves the active profile's icons and locks the refresh-made ones in (from now on a
     * refresh replaces none of them — the user clears icons or hand-edits to change them).
     * [unbuiltAfter] records whether this save is still waiting for a build.
     */
    private suspend fun persistActiveProfileIcons(unbuiltAfter: Boolean) = withContext(Dispatchers.Default) {
        saveRenkinPack()
        for (app in applicationList.toList()) {
            if (app.isRefreshMade) editApplication(app, app.locked())
        }
        packRepo.profile(activeProfileId)?.let {
            packRepo.updateProfile(it.copy(hasUnbuiltChanges = unbuiltAfter))
        }
    }

    private suspend fun loadRenkinPack() {
        val saved = renkinPackStore.load(activeProfileId, defaultColor)
        if (saved.isEmpty()) return

        for (app in applicationList.toList()) {
            val entry = saved[app.key] ?: continue
            val updated = when {
                // Loaded from the DB = built/saved before, so it arrives locked (isRefreshMade
                // defaults to false): a refresh won't replace it.
                entry.icon != null -> app.changeExport(entry.icon, sourcePackName = entry.sourcePackName).changeCalendar(entry.calendarEnabled, entry.calendarPrefix, entry.calendarPackName)
                else -> app.changeCalendar(entry.calendarEnabled, entry.calendarPrefix, entry.calendarPackName)
            }
            editApplication(app, updated)
        }
    }

    /** Sets the calendar-day-icons flag, prefix, and source pack for [app]. */
    fun setCalendar(app: PackageInfoStruct, enabled: Boolean, calendarPrefix: String?, calendarPackName: String?) {
        val index = _applicationList.indexOfFirst {
            it.packageName == app.packageName && it.activityName == app.activityName
        }
        if (index >= 0) editApplication(index, _applicationList[index].changeCalendar(enabled, calendarPrefix, calendarPackName))
    }

    private suspend fun saveRenkinPack() = renkinPackStore.save(activeProfileId, applicationList)

    suspend fun forceSync() {
        if (iconPackRepo.iconPackLoaded) {
            iconPackRepo.load()
        }
    }

    private fun editApplication(oldApp: PackageInfoStruct, newApp: PackageInfoStruct) {
        val index = applicationList.indexOf(oldApp)
        if (index >= 0)
            editApplication(index, newApp)
    }

    fun editApplication(index: Int, newApp: PackageInfoStruct) {
        _applicationList[index] = newApp
    }

    suspend fun getIconPackIcons(iconPackName: String, options: GenerationOptions, drawables: List<ResourceDrawable>): Map<ResourceDrawable, IconPackDrawable?> =
        iconGenService.getIconPackIcons(iconPackName, options, drawables)

    suspend fun getIconPackDropdownIcons(application: InstalledApplication?): Map<String, ResourceDrawable> =
        iconPackRepo.getDropdownIcons(application)

    /**
     * Clears only the unsaved bulk-refresh icons (isRefreshMade); hand-picked and built/saved
     * icons stay. Used when the primary source is set to None: whatever the refresh produced
     * and nothing has locked in yet simply goes away. Nothing is persisted — these icons were
     * never saved.
     */
    fun clearRefreshedIcons() {
        for (app in applicationList.toList()) {
            if (app.isRefreshMade) editApplication(app, app.changeExport(null))
        }
    }

    suspend fun clearIcons() = withContext(Dispatchers.Default) {
        // Snapshot copy: editApplication mutates the live list in place.
        // Also reset calendar opt-ins: otherwise a calendar-enabled app is still persisted
        // (RenkinPackStore keeps calendar rows even without an icon), so it lingers in the
        // saved pack and the change bar counts it as a pending removal (a phantom "−1").
        for (app in applicationList.toList()) {
            editApplication(app, app.changeExport(null).changeCalendar(false, null, null))
        }
        // Persist the cleared state, otherwise the saved pack reloads the icons on the
        // next launch and "Remove icons" looks like it did nothing.
        saveRenkinPack()
    }

    /** Keys ("package/activity") of the apps stored in the last built/saved pack. */
    suspend fun getSavedPackKeys(): Set<String> = renkinPackStore.savedKeys(activeProfileId)

    // ---- Profiles -----------------------------------------------------------------

    fun profilesFlow() = packRepo.profilesFlow()

    suspend fun activeProfile(): Profile? = packRepo.profile(activeProfileId)

    /** The package name the active profile's pack builds under (base pack for the default). */
    suspend fun activePackPackageName(): String = packPackageNameFor(activeProfileId)

    private fun packPackageNameFor(profileId: Long): String =
        if (profileId == DEFAULT_PROFILE_ID) IconPackBuilder.PACKAGE_NAME
        else "${IconPackBuilder.PACKAGE_NAME}.p$profileId"

    /** Creates a profile and returns its id. */
    suspend fun createProfile(name: String, description: String, packLabel: String): Long =
        packRepo.createProfile(Profile(name = name, description = description, packLabel = packLabel))

    /** Deletes [id] (never the default) and its icons; switches to the default first if active. */
    suspend fun deleteProfile(id: Long) {
        if (id == DEFAULT_PROFILE_ID) return
        if (id == activeProfileId) switchProfile(DEFAULT_PROFILE_ID)
        packRepo.deleteProfile(id)
        // Drop the profile's watch rules too, or the periodic worker keeps checking them and
        // fires notifications that deep-link into a profile that no longer exists.
        WatchRepository(context).deleteRulesForProfile(id)
    }

    /** True while [id] still names an existing profile — deleted-profile deep links check this. */
    suspend fun profileExists(id: Long): Boolean = packRepo.profile(id) != null

    /**
     * Switches the active profile: snapshots the leaving profile's generation preferences,
     * restores the target's, and swaps the in-memory icons for the target's saved set. For the
     * leaving profile this behaves like an app restart — unbuilt edits are not persisted.
     */
    suspend fun switchProfile(newProfileId: Long) = withContext(Dispatchers.Default) {
        if (newProfileId == activeProfileId) return@withContext
        val target = packRepo.profile(newProfileId) ?: return@withContext
        val store = context.dataStore

        packRepo.profile(activeProfileId)?.let { leaving ->
            packRepo.updateProfile(leaving.copy(prefsSnapshot = store.data.first().snapshotProfilePrefs()))
        }
        store.restoreProfilePrefs(target.prefsSnapshot)
        store.edit { it[ActiveProfileIdKey] = newProfileId }
        activeProfileId = newProfileId

        // Reset the in-memory icons and load the target profile's saved set.
        for (app in applicationList.toList()) {
            editApplication(app, app.changeExport(null).changeCalendar(false, null, null))
        }
        loadRenkinPack()
    }

    /**
     * Live count of icons taken from each pack, from the in-memory app list — so the per-app
     * picker reflects icons the moment they're assigned (generated or hand-picked), not only
     * after a build persists them. The list is seeded from the DB on startup, so saved counts
     * survive restarts too. Keyed by pack package name; non-pack icons (empty source) are skipped.
     */
    fun packUsageCounts(): Map<String, Int> =
        applicationList
            .mapNotNull { it.sourcePackName?.takeIf { source -> source.isNotEmpty() } }
            .groupingBy { it }
            .eachCount()

    /**
     * A few sample icons styled with [fallbackSource]'s fallback, for the Options preview so the
     * user sees the look before building. Empty when NONE or the source pack declares no fallback.
     */
    suspend fun fallbackPreview(preferences: Preferences, fallbackSource: FallbackSource): List<IconPackDrawable> =
        withContext(Dispatchers.Default) {
            if (fallbackSource == FallbackSource.NONE) return@withContext emptyList()
            val options = GenerationOptions.fromPreferences(preferences, context).copy(fallbackSource = fallbackSource)
            iconGenService.fallbackPreview(options, applicationList.take(4))
        }

    /** Of [prefixes], those that are genuine calendar day-rotation sets in [packPackageName]. */
    suspend fun calendarPrefixesAmong(packPackageName: String, prefixes: List<String>): Set<String> =
        withContext(Dispatchers.Default) {
            prefixes.filter { iconPackRepo.isCalendarPrefix(packPackageName, it) }.toSet()
        }

    /** A calendar-enabled app whose source pack is missing some of the 1..31 day drawables. */
    data class CalendarWarning(val appName: String, val missingDays: Int)

    /**
     * Checks every calendar-enabled app against its source pack and reports those missing day
     * drawables (which would repeat a fallback icon instead of rotating). Uses the same
     * [PackageInfoStruct.hasCalendarIcon] / [PackageInfoStruct.calendarSourcePack] selection as
     * [buildAndSignIconPack], so the warning matches what the build will actually emit.
     */
    suspend fun calendarWarnings(preferences: Preferences): List<CalendarWarning> = withContext(Dispatchers.Default) {
        val primaryPackName = preferences.getStringValue(PrimaryIconPackKey)
        applicationList
            .filter { it.hasCalendarIcon }
            .mapNotNull { app ->
                val packName = app.calendarSourcePack(primaryPackName)
                if (packName.isEmpty()) return@mapNotNull null
                val missing = iconPackRepo.missingCalendarDays(packName, app.calendarPrefix!!)
                if (missing.isNotEmpty()) CalendarWarning(app.appName, missing.size) else null
            }
    }

    data class BuiltIconPack(
        val uri: Uri,
        val packageName: String,
        val canBeInstalled: Boolean
    )
}