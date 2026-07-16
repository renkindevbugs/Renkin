package dev.renkinProject.renkin.apk

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.CalendarIconsKey
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.ExportThemedKey
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.PrimaryIconPackKey
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.OverrideIconKey
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.FallbackSource
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal suspend fun installOrReportConflict(
    canUpdateInPlace: Boolean,
    install: suspend () -> ApkInstallResult
): ApkInstallResult =
    if (canUpdateInPlace) install() else ApkInstallResult.CONFLICT

internal suspend fun replaceAfterConflict(
    uninstall: suspend () -> Boolean,
    install: suspend () -> ApkInstallResult
): ApkInstallResult =
    if (uninstall()) install() else ApkInstallResult.ABORTED

/**
 * The domain hub between the UI layer (MainViewModel) and everything icon-related: the live
 * app list, icon generation/refresh, pack building and the saved-pack store. Profile state
 * lives in [ProfileManager], held-back rows and verdict policy in [IconLockManager] — this
 * class orchestrates them around the one thing only it owns: [applicationList].
 */
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

    private val renkinPackStore = RenkinPackStore(context)
    private val packRepo = RenkinPackRepository(context)
    private val iconPackRepo = IconPackRepository(context)
    private val lockManager = IconLockManager(context, packRepo)
    private val profileManager = ProfileManager(context, packRepo)

    /** Keys of the active profile's icons currently locked behind a missing pack. */
    val lockedIconKeys: Set<String> get() = lockManager.lockedIconKeys

    /** The profile whose icons/preferences are active. Set before the saved pack loads. */
    val activeProfileId: Long get() = profileManager.activeProfileId

    private val iconGenService = IconGenerationService(context, iconPackRepo)

    private val appManager: ApplicationManager by lazy { ApplicationManager(context) }

    suspend fun initialize() {
        initializeApplications()
        initializeIconPacks()
        initializeRenkinPack()
    }

    /**
     * Re-discovers apps/packs and reloads persisted profile state without discarding the keys
     * edited in this session. Nothing is written to storage: a process restart still returns to
     * the last build/save. If reloading fails after clearing the list, restore the old session.
     */
    suspend fun reloadPreservingSession(preserveKeys: Set<String>) {
        val session = applicationList.toList()
        try {
            initialize()
            replaceApplications(mergeApplicationReload(session, applicationList.toList(), preserveKeys))
        } catch (error: Exception) {
            replaceApplications(session)
            throw error
        }
    }

    suspend fun initializeApplications() = withContext(Dispatchers.Default) {
        val apps = appManager.getAllInstalledApps()
        apps.sort()

        replaceApplications(apps.asList())
        applicationsLoaded = true
    }

    private fun replaceApplications(apps: List<PackageInfoStruct>) {
        _applicationList.clear()
        _applicationList.addAll(apps)
    }

    suspend fun initializeIconPacks() {
        iconPackRepo.load()
        // Every pack seen installed is owned on this device forever — the paid-pack lock
        // never applies to it again, even after an uninstall.
        lockManager.recordInstalledPacks(iconPacks)
    }

    suspend fun initializeRenkinPack() {
        profileManager.initActiveId()
        loadRenkinPack()
        // MainViewModel's startup calls the initialize* steps individually (not initialize()),
        // and this one runs last of the two that profile switching depends on (apps + saved
        // icons) — so THIS is where switching becomes safe, not initialize().
        startupComplete = true
    }

    suspend fun refreshIcon(application: PackageInfoStruct, preferences: Preferences) = withContext(Dispatchers.Default) {
        // A newly installed app always gets its icon (re)generated
        val genOptions = GenerationOptions.fromPreferences(preferences, context, override = true)
        val lockedOrigins = lockManager.lockedOriginsFor(genOptions)
        iconGenService.refreshIcon(application, genOptions) { app, icon, sourcePack ->
            val origin = lockManager.resolveOrigin(app.key, sourcePack)
            // An own pack handing out an icon whose real origin isn't owned here → withhold.
            if (icon == null || origin == null || origin !in lockedOrigins) {
                editApplication(app, app.changeExport(icon, sourcePackName = origin, isRefreshMade = true, isCustom = false))
            }
        }
    }

    /** Regenerates all icons. Returns how many were withheld because their real origin
     * (recorded by an own pack used as source) is a pack this device doesn't own. */
    suspend fun refreshIcons(preferences: Preferences): Int = withContext(Dispatchers.Default) {
        var opt = GenerationOptions.fromPreferences(preferences, context)
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
            else applicationList.toList().filter { it.key !in lockManager.lockedKeys }
        val lockedOrigins = lockManager.lockedOriginsFor(opt)
        var withheld = 0
        iconGenService.refreshIcons(targets, opt) { application, icon, isFallback, sourcePack ->
            val origin = lockManager.resolveOrigin(application.key, sourcePack) ?: sourcePack
            if (icon != null && origin in lockedOrigins) {
                // An own pack used as source handed out an icon whose real origin isn't
                // owned on this device — withhold it (the slot stays empty).
                withheld++
            } else {
                editApplication(application, application.changeExport(icon, isFallback, origin, isRefreshMade = true, isCustom = false))
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

    suspend fun getValidatedIconFromPackDrawable(
        application: PackageInfoStruct,
        packPackage: String,
        drawableName: String,
        expectedHash: String,
        options: GenerationOptions
    ): IconGenerationService.ValidatedPackIcon =
        iconGenService.getValidatedIconFromPackDrawable(
            application, packPackage, drawableName, expectedHash, options
        )

    /** Applies the modifier from [options] to an already-built icon (e.g. a hand-edited vector). */
    suspend fun applyModifier(icon: IconPackDrawable, options: GenerationOptions): IconPackDrawable =
        iconGenService.applyModifier(icon, options)

    /**
     * Bakes the global modifiers into the stored icons (the Global options screen's Save):
     * icons in the toggled-on categories get [modifierOptions] applied over their current
     * pixels — refresh-generated icons when [applyGenerated], custom (hand-picked) icons when
     * [applyCustom] — and apps with no icon get one generated from the current preferences
     * when [includeEmpty] (the prefs already carry the globals). The result is persisted like
     * a save-before-switch, so shares/exports carry the baked icons. Icons locked behind a
     * missing pack are never touched.
     */
    suspend fun applyGlobalModifiers(
        preferences: Preferences,
        modifierOptions: GenerationOptions,
        applyGenerated: Boolean,
        applyCustom: Boolean,
        includeEmpty: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.Default) {
        val targets = applicationList.toList().filter { app ->
            when {
                app.key in lockManager.lockedKeys -> false
                app.createdIcon != null -> if (app.isCustom) applyCustom else applyGenerated
                else -> includeEmpty
            }
        }
        var done = 0
        onProgress(0, targets.size)
        for (app in targets) {
            val icon = app.createdIcon
            if (icon != null) {
                val baked = runCatching { iconGenService.applyModifier(icon, modifierOptions) }
                    .getOrNull()
                if (baked != null) {
                    editApplication(app, app.changeExport(baked))
                }
            } else {
                // Same generation + locked-origin gate as a bulk refresh, one app at a time
                // (the row is edited — or withheld — inside refreshIcon).
                refreshIcon(app, preferences)
            }
            done++
            onProgress(done, targets.size)
        }
        // Persisting is what makes the bake real: shares/exports read the saved rows.
        saveActiveProfileIcons()
    }

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

            // Resolve calendar data at build time from the current preferences and installed
            // packs. There is deliberately no mutable cache: building immediately after startup,
            // changing the primary pack, or disabling global calendars must all use current data.
            val globalSelections = if (
                preferences.getBooleanValue(CalendarIconsKey) && primaryPackName.isNotEmpty()
            ) iconPackRepo.declaredCalendarSelections(primaryPackName) else emptyList()
            val perAppSelections = applicationList
                .filter { it.hasCalendarIcon }
                .mapNotNull { app ->
                    val packName = app.calendarSourcePack(primaryPackName)
                    packName.takeIf { it.isNotEmpty() }?.let {
                        CalendarSelection(app.toInstalledApplication(), it, app.calendarPrefix!!)
                    }
                }
            // buildCalendarData applies later selections last, so an explicit per-app choice
            // replaces only the same launcher component's global declaration.
            val calendarData = iconPackRepo.calendarBuildData(globalSelections + perAppSelections)

            // Final gate: icons whose (already translated) source pack is locked on this
            // device must not ship in the APK — belt-and-braces for anything that slipped
            // into the session between load-time lock evaluations.
            val lockedSources = lockManager.lockedPacksAmong(
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
                calendarData.mappings,
                calendarData.drawables,
                packPackageName = profileManager.packPackageNameFor(activeProfileId),
                packLabel = profile?.packLabel?.ifEmpty { profile.name } ?: "Renkin Pack"
            )
            val canBeInstalled = iconPackGenerator.canBeInstalled() // must be called before build and sign
            val apk = iconPackGenerator.buildAndSign(themed, iconColor.toHexString(), bgColor.toHexString(), textMethod, progressMethod)

            BuiltIconPack(apk, iconPackGenerator.getIconPackName(), canBeInstalled)
        }

    suspend fun installIconPack(iconPack: BuiltIconPack): ApkInstallResult = withContext(Dispatchers.Default) {
        val result = installOrReportConflict(iconPack.canBeInstalled) {
            ApkInstaller(context).install(iconPack.uri)
        }
        finishInstallAttempt(result)
        result
    }

    /** Explicitly approved fallback after an update conflict: uninstall, then install the APK. */
    suspend fun replaceIconPack(iconPack: BuiltIconPack): ApkInstallResult = withContext(Dispatchers.Default) {
        val result = replaceAfterConflict(
            uninstall = { ApkUninstaller(context).uninstall(iconPack.packageName) },
            install = { ApkInstaller(context).install(iconPack.uri) }
        )
        finishInstallAttempt(result)
        result
    }

    private suspend fun finishInstallAttempt(result: ApkInstallResult) {
        val success = result == ApkInstallResult.SUCCESS
        // A successful build IS the pack — the save matches it. A failed/cancelled install
        // leaves the save marked as not yet built.
        persistActiveProfileIcons(unbuiltAfter = !success)

        // The just-(re)installed pack carries a fresh provenance map.
        if (success) lockManager.clearProvenanceCache()
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
        profileManager.markActiveUnbuilt(unbuiltAfter)
    }

    private suspend fun loadRenkinPack() {
        lockManager.clear()
        val prefs = context.dataStore.data.first()
        // Saved vectors may contain theme references. Resolve their fallback from the user's
        // explicit theme choice every time a profile is loaded, not from stale system state.
        val defaultColor = prefs.getDefaultIconColor(context)
        val saved = renkinPackStore.load(activeProfileId, defaultColor)
        if (saved.isEmpty()) return

        // Which source packs' icons must stay locked on this device (paid or unverified,
        // not installed, never seen installed) — see PackVerdictManager.
        val lockedPacks = lockManager.lockedPacksAmong(
            saved.values.mapNotNull { it.sourcePackName }.toSet()
        )

        // Rows for apps not installed here (imported sets) survive saves untouched and
        // load normally once the app appears.
        val appKeys = applicationList.map { it.key }.toSet()
        for ((key, entry) in saved) {
            if (key !in appKeys) lockManager.holdOrphan(key, entry.row)
        }

        for (app in applicationList.toList()) {
            val entry = saved[app.key] ?: continue
            if (entry.sourcePackName != null && entry.sourcePackName in lockedPacks) {
                // Held back: invisible to the list and the build, preserved by saves,
                // loaded normally once the pack is installed (or verified free).
                lockManager.lock(app.key, entry.row)
                continue
            }
            // A reference row (no image data, source pack known) whose pack is usable:
            // rebuild the icon from the pack. Failure keeps the row held back for retry.
            val isReference = entry.icon == null && entry.row.drawable.isEmpty() && entry.sourcePackName != null
            val icon = if (isReference) {
                val rebuilt = materializeReference(app, entry, prefs)
                if (rebuilt == null) {
                    lockManager.lock(app.key, entry.row)
                    continue
                }
                rebuilt
            } else entry.icon
            val updated = when {
                // Loaded from the DB = built/saved before, so it arrives locked (isRefreshMade
                // defaults to false): a refresh won't replace it.
                icon != null -> app.changeExport(icon, sourcePackName = entry.sourcePackName, isCustom = entry.isCustom).changeCalendar(entry.calendarEnabled, entry.calendarPrefix, entry.calendarPackName)
                else -> app.changeCalendar(entry.calendarEnabled, entry.calendarPrefix, entry.calendarPackName)
            }
            editApplication(app, updated)
        }
        lockManager.publish()
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
        lockManager.releaseReplaced(replacedKeys)
        renkinPackStore.save(activeProfileId, applicationList, lockManager.preservedRows())
    }

    suspend fun forceSync() {
        if (iconPackRepo.iconPackLoaded) {
            iconPackRepo.load()
            lockManager.recordInstalledPacks(iconPacks)
            // Packs may have been updated/rebuilt — their provenance maps can be stale.
            lockManager.clearProvenanceCache()
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
        if (lockManager.isEmpty()) return@withContext
        val stillLocked = lockManager.lockedPacksAmong(lockManager.lockedSourcePacks().toSet())
        val prefs = context.dataStore.data.first()
        val defaultColor = prefs.getDefaultIconColor(context)
        for ((key, row) in lockManager.lockedRowsSnapshot()) {
            if (row.sourcePackName in stillLocked) continue
            val app = applicationList.firstOrNull { it.key == key } ?: continue
            val entry = renkinPackStore.decodeRow(row, defaultColor)
            val icon = entry.icon ?: materializeReference(app, entry, prefs) ?: continue
            editApplication(
                app,
                app.changeExport(icon, sourcePackName = entry.sourcePackName, isCustom = entry.isCustom)
                    .changeCalendar(entry.calendarEnabled, entry.calendarPrefix, entry.calendarPackName)
            )
            lockManager.release(key)
        }
        lockManager.publish()
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
        lockManager.clear()
        // Snapshot copy: editApplication mutates the live list in place.
        // Also reset calendar opt-ins: otherwise a calendar-enabled app is still persisted
        // (RenkinPackStore keeps calendar rows even without an icon), so it lingers in the
        // saved pack and the change bar counts it as a pending removal (a phantom "−1").
        resetInMemoryIcons()
        // Persist the cleared state, otherwise the saved pack reloads the icons on the
        // next launch and "Remove icons" looks like it did nothing.
        saveRenkinPack()
    }

    /** Blanks every in-memory icon and calendar opt-in (profile switch, restore, clear). */
    private fun resetInMemoryIcons() {
        for (app in applicationList.toList()) {
            editApplication(app, app.changeExport(null).changeCalendar(false, null, null))
        }
    }

    /** Keys ("package/activity") of the apps stored in the last built/saved pack. */
    suspend fun getSavedPackKeys(): Set<String> = renkinPackStore.savedKeys(activeProfileId)

    // ---- Profiles (delegated to ProfileManager) -------------------------------------

    fun profilesFlow() = profileManager.profilesFlow()

    suspend fun activeProfile(): Profile? = profileManager.activeProfile()

    /** The package name the active profile's pack builds under (base pack for the default). */
    suspend fun activePackPackageName(): String = profileManager.packPackageNameFor(activeProfileId)

    /** Creates a profile and returns its id. */
    suspend fun createProfile(name: String, description: String, packLabel: String): Long =
        profileManager.createProfile(name, description, packLabel)

    suspend fun updateProfileDetails(id: Long, name: String, description: String, packLabel: String) =
        profileManager.updateProfileDetails(id, name, description, packLabel)

    /** Deletes [id] (never the default) and its icons; switches to the default first if active. */
    suspend fun deleteProfile(id: Long) {
        if (id == DEFAULT_PROFILE_ID) return
        if (id == activeProfileId) switchProfile(DEFAULT_PROFILE_ID)
        profileManager.deleteProfile(id)
    }

    /** True while [id] still names an existing profile — deleted-profile deep links check this. */
    suspend fun profileExists(id: Long): Boolean = profileManager.profileExists(id)

    /** Persists the active profile's "don't show the missing-packs dialog again" choice. */
    suspend fun setHideMissingPackWarning(hide: Boolean) = profileManager.setHideMissingPackWarning(hide)

    /**
     * Switches the active profile: snapshots the leaving profile's generation preferences,
     * restores the target's, and swaps the in-memory icons for the target's saved set. For the
     * leaving profile this behaves like an app restart — unbuilt edits are not persisted.
     */
    suspend fun switchProfile(newProfileId: Long) = withContext(Dispatchers.Default) {
        if (!profileManager.switchTo(newProfileId)) return@withContext
        // Reset the in-memory icons and load the target profile's saved set.
        resetInMemoryIcons()
        loadRenkinPack()
    }

    /**
     * Re-reads the active profile id and its saved icons after a backup import replaced the
     * stores under the running app. Same in-memory reset as a profile switch, minus the
     * snapshot of the leaving state — that state was just overwritten on purpose.
     */
    suspend fun reloadActiveProfile() = withContext(Dispatchers.Default) {
        profileManager.reloadActiveId()
        resetInMemoryIcons()
        loadRenkinPack()
    }

    // ---- Locks & verdicts (delegated to IconLockManager) -----------------------------

    /**
     * Looks up any referenced pack still lacking a paid/free verdict (quiet best effort).
     * Returns true when a verdict became decisive — callers reload so freshly-verified-free
     * icons unlock without a restart.
     */
    suspend fun verifyPendingVerdicts(): Boolean = lockManager.verifyPendingVerdicts()

    /** The active profile's locked icons grouped by their missing source pack. */
    suspend fun missingPackSummary(): List<IconLockManager.MissingPack> = lockManager.missingPackSummary()

    /**
     * The hand-pick path's provenance gate: translates an own-pack source to the origin its
     * provenance records and says whether that origin is locked here.
     */
    suspend fun resolvePickedSource(app: PackageInfoStruct, sourcePackName: String?): Pair<String?, Boolean> =
        lockManager.resolvePickedSource(app.key, sourcePackName)

    /** Removes a held-back imported icon after an explicit per-app reset. */
    fun discardLockedIcon(key: String) = lockManager.discard(key)

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
                lockManager.lockedSourcePacks()
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

    /**
     * Drawable names [packPackageName]'s appfilter declares as `<dynamic-clock>` icons —
     * the pack browser badges them so a live-clock pick is recognisable before building.
     */
    suspend fun dynamicClockDrawables(packPackageName: String): Set<String> =
        withContext(Dispatchers.Default) {
            runCatching { appManager.getAppFilterRawElements(packPackageName, emptyList()) }
                .getOrDefault(emptyList())
                .filterIsInstance<dev.renkinProject.renkin.data.RawDynamicClock>()
                .map { it.drawableLink }
                .toSet()
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
