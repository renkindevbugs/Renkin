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
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.OverrideIconKey
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.restoreProfilePrefs
import dev.renkinProject.renkin.data.snapshotProfilePrefs
import dev.renkinProject.renkin.data.VERDICT_UNKNOWN
import dev.renkinProject.renkin.data.transfer.PackVerdictManager
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
    private val verdictManager = PackVerdictManager(context, packRepo)

    // Rows held back from the in-memory list but preserved across saves:
    // icons locked behind a missing paid/unverified pack (or references that couldn't be
    // rebuilt yet), keyed like the app list...
    private val lockedRows = mutableMapOf<String, DbApplication>()
    // ...and icons of apps not installed on this device (from imported profiles/backups) —
    // they come back through a normal load once the app is installed.
    private val orphanRows = mutableMapOf<String, DbApplication>()

    /** Keys of the active profile's icons currently locked behind a missing pack. Compose
     * state (snapshot of the internal map) so the list rows/badges react to lock changes. */
    var lockedIconKeys: Set<String> by mutableStateOf(emptySet())
        private set

    // Session cache of the provenance maps carried by installed Renkin-built packs
    // (component key → original source pack). Cleared when packs re-sync or rebuild.
    private val provenanceCache = mutableMapOf<String, Map<String, String>>()

    private fun provenanceFor(packPackage: String): Map<String, String> =
        provenanceCache.getOrPut(packPackage) { PackProvenance.read(context, packPackage) }

    /**
     * The REAL origin of an icon sourced from [sourcePack]: our own built packs carry a
     * provenance map, so an icon that was originally taken from pack X stays attributed to
     * X even when it arrives through a Renkin pack. Foreign packs pass through unchanged.
     */
    private fun resolveOrigin(appKey: String, sourcePack: String?): String? {
        val source = sourcePack?.takeIf { it.isNotEmpty() } ?: return sourcePack
        if (!IconPackBuilder.isOwnPack(source)) return source
        return provenanceFor(source)[appKey] ?: source
    }

    /**
     * Origins recorded by the own packs among [options]' sources that are locked on this
     * device — precomputed so the (non-suspend) generation callbacks can gate on it.
     */
    private suspend fun lockedOriginsFor(options: GenerationOptions): Set<String> {
        val ownPacks = listOf(options.primaryIconPack, options.secondaryIconPack)
            .filter { it.isNotEmpty() && IconPackBuilder.isOwnPack(it) }
        if (ownPacks.isEmpty()) return emptySet()
        return verdictManager.lockedPacksAmong(ownPacks.flatMap { provenanceFor(it).values }.toSet())
    }

    private fun publishLockedKeys() {
        lockedIconKeys = lockedRows.keys.toSet()
    }

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

    suspend fun initializeIconPacks() {
        iconPackRepo.load()
        // Every pack seen installed is owned on this device forever — the paid-pack lock
        // never applies to it again, even after an uninstall.
        verdictManager.recordInstalledPacks(iconPacks)
    }

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
        val lockedOrigins = lockedOriginsFor(genOptions)
        iconGenService.refreshIcon(application, genOptions) { app, icon, sourcePack ->
            val origin = resolveOrigin(app.key, sourcePack)
            // An own pack handing out an icon whose real origin isn't owned here → withhold.
            if (icon == null || origin == null || origin !in lockedOrigins) {
                editApplication(app, app.changeExport(icon, sourcePackName = origin, isRefreshMade = true))
            }
        }
    }

    /** Regenerates all icons. Returns how many were withheld because their real origin
     * (recorded by an own pack used as source) is a pack this device doesn't own. */
    suspend fun refreshIcons(preferences: Preferences): Int = withContext(Dispatchers.Default) {
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
        // Apps whose icon is locked behind a missing pack look empty but must not be
        // silently refilled — the original would be lost on save. The explicit "Refresh
        // replaces existing icons" switch bypasses this like every other lock.
        val targets = if (preferences.getBooleanValue(OverrideIconKey)) applicationList.toList()
            else applicationList.toList().filter { it.key !in lockedRows.keys }
        val lockedOrigins = lockedOriginsFor(opt)
        var withheld = 0
        iconGenService.refreshIcons(targets, opt) { application, icon, isFallback, sourcePack ->
            val origin = resolveOrigin(application.key, sourcePack) ?: sourcePack
            if (icon != null && origin in lockedOrigins) {
                // An own pack used as source handed out an icon whose real origin isn't
                // owned on this device — withhold it (the slot stays empty).
                withheld++
            } else {
                editApplication(application, application.changeExport(icon, isFallback, origin, isRefreshMade = true))
            }
        }
        withheld
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

            // Final gate: icons whose (already translated) source pack is locked on this
            // device must not ship in the APK — belt-and-braces for anything that slipped
            // into the session between load-time lock evaluations.
            val lockedSources = verdictManager.lockedPacksAmong(
                applicationList.mapNotNull { it.sourcePackName?.takeIf { s -> s.isNotEmpty() } }.toSet()
            )
            val buildApps = if (lockedSources.isEmpty()) applicationList.toList()
                else applicationList.map { app ->
                    if (app.sourcePackName in lockedSources) app.changeExport(null) else app
                }

            // Each profile builds its own pack: a per-profile package name (side-by-side
            // installs) and the user's chosen launcher label.
            val profile = packRepo.profile(activeProfileId)
            val iconPackGenerator = IconPackBuilder(
                context,
                buildApps,
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

        // The just-(re)installed pack carries a fresh provenance map.
        if (success) provenanceCache.clear()

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
        lockedRows.clear()
        orphanRows.clear()
        publishLockedKeys()
        val saved = renkinPackStore.load(activeProfileId, defaultColor)
        if (saved.isEmpty()) return

        // Which source packs' icons must stay locked on this device (paid or unverified,
        // not installed, never seen installed) — see PackVerdictManager.
        val lockedPacks = verdictManager.lockedPacksAmong(
            saved.values.mapNotNull { it.sourcePackName }.toSet()
        )

        // Rows for apps not installed here (imported sets) survive saves untouched and
        // load normally once the app appears.
        val appKeys = applicationList.map { it.key }.toSet()
        for ((key, entry) in saved) {
            if (key !in appKeys) orphanRows[key] = entry.row
        }

        val prefs = context.dataStore.data.first()
        for (app in applicationList.toList()) {
            val entry = saved[app.key] ?: continue
            if (entry.sourcePackName != null && entry.sourcePackName in lockedPacks) {
                // Held back: invisible to the list and the build, preserved by saves,
                // loaded normally once the pack is installed (or verified free).
                lockedRows[app.key] = entry.row
                continue
            }
            // A reference row (image data stripped at share time) whose pack is usable:
            // rebuild the icon from the pack. Failure keeps the row held back for retry.
            val isReference = entry.icon == null && entry.row.drawable.isEmpty() && entry.sourcePackName != null
            val icon = if (isReference) {
                val rebuilt = materializeReference(app, entry, prefs)
                if (rebuilt == null) {
                    lockedRows[app.key] = entry.row
                    continue
                }
                rebuilt
            } else entry.icon
            val updated = when {
                // Loaded from the DB = built/saved before, so it arrives locked (isRefreshMade
                // defaults to false): a refresh won't replace it.
                icon != null -> app.changeExport(icon, sourcePackName = entry.sourcePackName).changeCalendar(entry.calendarEnabled, entry.calendarPrefix, entry.calendarPackName)
                else -> app.changeCalendar(entry.calendarEnabled, entry.calendarPrefix, entry.calendarPackName)
            }
            editApplication(app, updated)
        }
        publishLockedKeys()
    }

    /**
     * Rebuilds a reference icon from its (present) source pack: by the drawable name the
     * share carried, otherwise through the pack's appfilter mapping for the component.
     */
    private suspend fun materializeReference(
        app: PackageInfoStruct,
        entry: RenkinPackStore.SavedEntry,
        preferences: Preferences
    ): IconPackDrawable? {
        val sourcePack = entry.sourcePackName ?: return null
        val drawableName = entry.row.sourceDrawableName.ifEmpty {
            appFilterDrawableName(sourcePack, app) ?: return null
        }
        val options = GenerationOptions.fromPreferences(preferences, context).copy(
            primarySource = Source.ICON_PACK,
            primaryImageEdit = ImageEdit.NONE,
            primaryIconPack = sourcePack
        )
        return runCatching {
            iconGenService.getIconFromPackDrawable(app, sourcePack, drawableName, options)
        }.getOrNull()
    }

    /** The drawable name [packPackage]'s appfilter maps to [app]'s component, if any. */
    private fun appFilterDrawableName(packPackage: String, app: PackageInfoStruct): String? =
        appManager.appFilterDrawableName(packPackage, app.toInstalledApplication())

    private suspend fun saveRenkinPack() {
        // A hand-picked or regenerated icon over a locked slot replaces the held-back original.
        val replacedKeys = applicationList.filter { it.createdIcon != null }.map { it.key }.toSet()
        lockedRows.keys.removeAll(replacedKeys)
        publishLockedKeys()
        renkinPackStore.save(activeProfileId, applicationList, lockedRows.values + orphanRows.values)
    }

    suspend fun forceSync() {
        if (iconPackRepo.iconPackLoaded) {
            iconPackRepo.load()
            verdictManager.recordInstalledPacks(iconPacks)
            // Packs may have been updated/rebuilt — their provenance maps can be stale.
            provenanceCache.clear()
            // A freshly installed pack may be exactly the one some icons were waiting for.
            refreshLockedIcons()
        }
    }

    /**
     * Re-evaluates the held-back rows after the installed packs changed: rows whose pack
     * just became usable load into the list right away — no restart or profile switch.
     * Unsaved session edits stay untouched; rows that still can't be rebuilt stay held back.
     */
    private suspend fun refreshLockedIcons() = withContext(Dispatchers.Default) {
        if (lockedRows.isEmpty()) return@withContext
        val stillLocked = verdictManager.lockedPacksAmong(
            lockedRows.values.map { it.sourcePackName }.filter { it.isNotEmpty() }.toSet()
        )
        val prefs = context.dataStore.data.first()
        for ((key, row) in lockedRows.toList()) {
            if (row.sourcePackName in stillLocked) continue
            val app = applicationList.firstOrNull { it.key == key } ?: continue
            val entry = renkinPackStore.decodeRow(row, defaultColor)
            val icon = entry.icon ?: materializeReference(app, entry, prefs) ?: continue
            editApplication(
                app,
                app.changeExport(icon, sourcePackName = entry.sourcePackName)
                    .changeCalendar(entry.calendarEnabled, entry.calendarPrefix, entry.calendarPackName)
            )
            lockedRows.remove(key)
        }
        publishLockedKeys()
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
        // "Remove icons" is explicit user intent — held-back rows (locked/absent apps) go too.
        lockedRows.clear()
        orphanRows.clear()
        publishLockedKeys()
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

    /**
     * Updates [id]'s user-facing details. The pack label only takes effect on the next build
     * (it's the launcher-visible label baked into the pack APK's manifest).
     */
    suspend fun updateProfileDetails(id: Long, name: String, description: String, packLabel: String) {
        val profile = packRepo.profile(id) ?: return
        packRepo.updateProfile(profile.copy(name = name, description = description, packLabel = packLabel))
    }

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
     * Looks up any referenced pack still lacking a paid/free verdict (quiet best effort).
     * Returns true when a verdict became decisive — callers reload so freshly-verified-free
     * icons unlock without a restart.
     */
    suspend fun verifyPendingVerdicts(): Boolean = verdictManager.verifyPendingVerdicts()

    /** One missing source pack and how many of the active profile's icons it locks. */
    data class MissingPack(val packageName: String, val label: String, val verdict: String, val iconCount: Int)

    /** The active profile's locked icons grouped by their missing source pack. */
    suspend fun missingPackSummary(): List<MissingPack> = withContext(Dispatchers.Default) {
        val byPack = lockedRows.values.groupBy { it.sourcePackName }.filterKeys { it.isNotEmpty() }
        if (byPack.isEmpty()) return@withContext emptyList()
        val verdicts = packRepo.verdicts(byPack.keys.toList())
        byPack.map { (pack, rows) ->
            val verdict = verdicts[pack]
            MissingPack(
                packageName = pack,
                label = verdict?.label?.ifEmpty { null } ?: pack,
                verdict = verdict?.verdict ?: VERDICT_UNKNOWN,
                iconCount = rows.size
            )
        }.sortedByDescending { it.iconCount }
    }

    /**
     * The hand-pick path's provenance gate: translates an own-pack source to the origin its
     * provenance records and says whether that origin is locked here. Foreign packs pass
     * through unlocked — an installed pack is usable by definition.
     */
    suspend fun resolvePickedSource(app: PackageInfoStruct, sourcePackName: String?): Pair<String?, Boolean> =
        withContext(Dispatchers.Default) {
            val origin = resolveOrigin(app.key, sourcePackName)
            if (origin == null || origin == sourcePackName) origin to false
            else origin to verdictManager.lockedPacksAmong(setOf(origin)).isNotEmpty()
        }

    /** One row of the "pack usage" stats: how many stored icons really came from [packageName]. */
    data class PackUsage(val packageName: String, val label: String, val count: Int, val installed: Boolean)

    /**
     * Per-pack usage by TRUE origin for the stats dialog: counts include the locked
     * (held-back) icons, and packs that aren't installed still show up — named from the
     * verdict cache when we've never seen them installed.
     */
    suspend fun packUsageEntries(): List<PackUsage> = withContext(Dispatchers.Default) {
        val counts = (
            applicationList.mapNotNull { it.sourcePackName?.takeIf { s -> s.isNotEmpty() } } +
                lockedRows.values.map { it.sourcePackName }.filter { it.isNotEmpty() }
            ).groupingBy { it }.eachCount()
        val installed = iconPacks.associateBy { it.packageName }
        val cachedLabels = packRepo.verdicts(counts.keys.filter { it !in installed })
        (installed.keys + counts.keys).distinct().map { pack ->
            PackUsage(
                packageName = pack,
                label = installed[pack]?.applicationName
                    ?: cachedLabels[pack]?.label?.ifEmpty { null }
                    ?: pack,
                count = counts[pack] ?: 0,
                installed = pack in installed
            )
        }.sortedWith(compareByDescending<PackUsage> { it.count }.thenBy { it.label.lowercase() })
    }

    /** Persists the active profile's "don't show the missing-packs dialog again" choice. */
    suspend fun setHideMissingPackWarning(hide: Boolean) {
        packRepo.profile(activeProfileId)?.let {
            packRepo.updateProfile(it.copy(hideMissingPackWarning = hide))
        }
    }

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
     * Re-reads the active profile id and its saved icons after a backup import replaced the
     * stores under the running app. Same in-memory reset as a profile switch, minus the
     * snapshot of the leaving state — that state was just overwritten on purpose.
     */
    suspend fun reloadActiveProfile() = withContext(Dispatchers.Default) {
        val storedId = context.dataStore.data.first()[ActiveProfileIdKey] ?: DEFAULT_PROFILE_ID
        // The stored id always exists in a well-formed backup; fall back defensively anyway.
        activeProfileId = if (packRepo.profile(storedId) != null) storedId else DEFAULT_PROFILE_ID
        if (activeProfileId != storedId) {
            context.dataStore.edit { it[ActiveProfileIdKey] = activeProfileId }
        }
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