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
import dev.renkinProject.renkin.data.BuiltPrimarySourceKey
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.DbApplication
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
import dev.renkinProject.renkin.data.GlobalApplyCustomKey
import dev.renkinProject.renkin.data.GlobalApplyExistingKey
import dev.renkinProject.renkin.data.GlobalApplyGeneratedKey
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.getDefaultBackgroundColor
import dev.renkinProject.renkin.data.getDefaultIconColor
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.data.persistGlobalModifierPrefs
import dev.renkinProject.renkin.data.restoreBuiltPrimarySource
import dev.renkinProject.renkin.data.online.onlineAttributionLabel
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.globalModifierOptions
import dev.renkinProject.renkin.icon.creator.hasVisibleModifierEffect
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.InstalledAppCatalog
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.extension.toHexString
import dev.renkinProject.renkin.packages.supportDynamicColors
import dev.renkinProject.renkin.dataStore
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

internal fun shouldApplyGlobalLayer(
    isCustom: Boolean,
    isRefreshMade: Boolean,
    applyGenerated: Boolean,
    applyExisting: Boolean,
    applyCustom: Boolean
): Boolean = when {
    isCustom -> applyCustom
    isRefreshMade -> applyGenerated
    else -> applyExisting
}

internal fun shouldProcessGlobalLayer(
    hasIcon: Boolean,
    isCustom: Boolean,
    isRefreshMade: Boolean,
    applyGenerated: Boolean,
    applyExisting: Boolean,
    applyCustom: Boolean,
    includeEmpty: Boolean
): Boolean = if (!hasIcon) {
    includeEmpty
} else {
    shouldApplyGlobalLayer(
        isCustom, isRefreshMade, applyGenerated, applyExisting, applyCustom
    )
}

/** Builds the all-or-nothing list used by Global Save without mutating the live Compose list. */
internal fun mergeGlobalRenders(
    original: List<PackageInfoStruct>,
    renderedByKey: Map<String, PackageInfoStruct>
): List<PackageInfoStruct> = original.map { renderedByKey[it.key] ?: it }

/** Serializes every operation that reads or replaces the active profile session. */
internal class ProfileOperationGate {
    private val mutex = Mutex()

    suspend fun <T> run(operation: suspend () -> T): T = mutex.withLock { operation() }
}

/**
 * The domain hub between the UI layer (MainViewModel) and everything icon-related: the live
 * app list, icon generation/refresh, pack building and the saved-pack store. Profile state
 * lives in [ProfileManager], held-back rows and verdict policy in [IconLockManager] — this
 * class orchestrates them around the one thing only it owns: [applicationList].
 */
class ApplicationProvider internal constructor(
    private val context: Context,
    private val renkinPackStore: RenkinPackStore,
    private val packRepo: RenkinPackRepository,
    private val iconPackRepo: IconPackRepository,
    private val lockManager: IconLockManager,
    private val profileManager: ProfileManager,
    private val iconGenService: IconGenerationService
) {
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

    /** Keys of the active profile's icons currently locked behind a missing pack. */
    val lockedIconKeys: Set<String> get() = lockManager.lockedIconKeys

    /** The profile whose icons/preferences are active. Set before the saved pack loads. */
    val activeProfileId: Long get() = profileManager.activeProfileId

    private val initialLoadMutex = Mutex()
    private val profileOperations = ProfileOperationGate()

    /** True while the shared preferences and live icon list are changing profiles. */
    var isProfileSwitching: Boolean by mutableStateOf(false)
        private set

    private val appManager: ApplicationManager by lazy { ApplicationManager(context) }
    private val installedAppCatalog: InstalledAppCatalog by lazy { InstalledAppCatalog(context) }

    /** Saved colours/gradients, shared by every profile. Read straight from the database flow. */
    fun colorPresets(): kotlinx.coroutines.flow.Flow<List<dev.renkinProject.renkin.data.ColorPreset>> =
        packRepo.colorPresetsFlow()

    suspend fun saveColorPreset(name: String, style: String) {
        packRepo.saveColorPreset(name, style)
    }

    suspend fun deleteColorPreset(id: Long) {
        packRepo.deleteColorPreset(id)
    }

    suspend fun initialize() {
        // Startup phase timings land in logcat (tag "Startup") so a slow cold start can be
        // attributed to a phase instead of guessed at. Debug-level: silent in release logs.
        timedPhase("apps") { initializeApplications() }
        timedPhase("icon packs") { initializeIconPacks() }
        timedPhase("saved icons") { initializeRenkinPack() }
    }

    private suspend fun timedPhase(name: String, block: suspend () -> Unit) {
        val startMs = android.os.SystemClock.elapsedRealtime()
        block()
        Log.debug("Startup", "$name loaded in ${android.os.SystemClock.elapsedRealtime() - startMs} ms")
    }

    /**
     * Cold recreation can open a secondary activity before MainViewModel exists. The first load
     * also restores the last-built primary selection while profile operations are blocked, so an
     * early profile-switch tap cannot move those preferences into a different profile.
     */
    suspend fun ensureInitialized() = initialLoadMutex.withLock {
        if (!startupComplete) {
            profileOperations.run {
                if (!startupComplete) {
                    val startupPrefs = context.dataStore.data.first()
                    if (startupPrefs.contains(BuiltPrimarySourceKey)) {
                        context.dataStore.restoreBuiltPrimarySource(startupPrefs)
                    }
                    initialize()
                }
            }
        }
    }

    /**
     * Re-discovers apps/packs and reloads persisted profile state without discarding the keys
     * edited in this session. Nothing is written to storage: a process restart still returns to
     * the last build/save. If reloading fails after clearing the list, restore the old session.
     */
    suspend fun reloadPreservingSession(preserveKeys: Set<String>) {
        profileOperations.run {
            val session = applicationList.toList()
            try {
                initialize()
                replaceApplications(mergeApplicationReload(session, applicationList.toList(), preserveKeys))
            } catch (error: Exception) {
                replaceApplications(session)
                throw error
            }
        }
    }

    suspend fun initializeApplications() = withContext(Dispatchers.Default) {
        val apps = installedAppCatalog.getAllInstalledApps()
        apps.sort()

        replaceApplications(apps.asList())
        applicationsLoaded = true
    }

    private fun replaceApplications(apps: List<PackageInfoStruct>) {
        _applicationList.clear()
        _applicationList.addAll(apps)
    }

    suspend fun initializeIconPacks() {
        iconPackRepo.load(installedApplicationRefs())
        // Every pack seen installed is owned on this device forever — the paid-pack lock
        // never applies to it again, even after an uninstall.
        lockManager.recordInstalledPacks(iconPacks)
    }

    /** The already-loaded app list as lightweight identity refs for appfilter matching. */
    private fun installedApplicationRefs(): List<InstalledApplication> =
        applicationList.map { InstalledApplication(it.packageName, it.activityName, it.iconID) }

    suspend fun initializeRenkinPack() {
        profileManager.initActiveId()
        loadRenkinPack(activeProfileId)
        // MainViewModel's startup calls the initialize* steps individually (not initialize()),
        // and this one runs last of the two that profile switching depends on (apps + saved
        // icons) — so THIS is where switching becomes safe, not initialize().
        startupComplete = true
    }

    /**
     * Regenerates one app's icon. Held under the same gate as [refreshIcons] — the recipe, the
     * live row and the undo capture must belong to one profile, or a switch landing mid-refresh
     * writes into the next profile's list. Returns true only when an existing icon was replaced,
     * which is the one case worth offering Undo for (filling an empty slot is what refresh is
     * for, and a withheld locked origin changes nothing at all).
     */
    suspend fun refreshIcon(expectedProfileId: Long, application: PackageInfoStruct): Boolean =
        withContext(Dispatchers.Default) {
            profileOperations.run {
                if (activeProfileId != expectedProfileId) return@run false
                val preferences = context.dataStore.getPreferencesAfterPendingWrites()
                // The row the user long-pressed may be a stale copy; regenerate over the live one.
                val target = applicationList.firstOrNull { it.key == application.key } ?: return@run false
                // A newly installed app always gets its icon (re)generated
                val sourceOptions = GenerationOptions.fromPreferences(preferences, context, override = true)
                val modifierOptions = modifierFor(preferences, apply = preferences.getBooleanValue(GlobalApplyGeneratedKey, true))
                val lockedOrigins = lockManager.lockedOriginsFor(sourceOptions)
                var replaced = false
                iconGenService.refreshIcon(target, sourceOptions, modifierOptions) { app, base, rendered, sourcePack ->
                    val origin = lockManager.resolveOrigin(app.key, sourcePack)
                    // An own pack handing out an icon whose real origin isn't owned here → withhold.
                    if (rendered == null || origin == null || origin !in lockedOrigins) {
                        val live = applicationList.firstOrNull { it.key == app.key }
                        if (live?.createdIcon != null) {
                            captureUndoRows(listOf(live), persisted = false)
                            replaced = true
                        }
                        editApplication(app, app.changeExport(rendered, sourcePackName = origin, isRefreshMade = true, isCustom = false, isLegacy = false, baseIcon = base))
                    }
                }
                replaced
            }
        }

    /**
     * Restores one app to its launcher default: drops an imported row held behind a missing pack
     * and clears calendar rotation. Under the gate for the same reason as [refreshIcon] — the
     * index lookup and the edit must see one list. Returns true when something was actually
     * cleared, so the caller only offers Undo then.
     */
    suspend fun resetIcon(expectedProfileId: Long, application: PackageInfoStruct): Boolean =
        withContext(Dispatchers.Default) {
            profileOperations.run {
                if (activeProfileId != expectedProfileId) return@run false
                val index = applicationList.indexOfFirst { it.key == application.key }
                if (index < 0) return@run false
                val live = applicationList[index]
                val hadSomething = live.createdIcon != null ||
                    live.calendarEnabled ||
                    application.key in lockManager.lockedKeys
                if (!hadSomething) return@run false
                captureUndoRows(listOf(live), persisted = true)
                lockManager.discard(application.key)
                editApplication(index, live.changeExport(null).changeCalendar(false, null, null))
                true
            }
        }

    /** Regenerates all icons. Returns how many were withheld because their real origin
     * (recorded by an own pack used as source) is a pack this device doesn't own. */
    suspend fun refreshIcons(expectedProfileId: Long): Int? = withContext(Dispatchers.Default) {
        // The preferences recipe and applicationList must belong to the same profile: a switch
        // landing between them would pair A's settings with B's list, and its wholesale list
        // swap would fight the per-app edits below. Read both under the switchProfile gate.
        profileOperations.run {
            if (activeProfileId != expectedProfileId) return@run null
            val preferences = context.dataStore.getPreferencesAfterPendingWrites()
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
            val modifierOptions = modifierFor(preferences, apply = preferences.getBooleanValue(GlobalApplyGeneratedKey, true))
            // Only rows that actually LOSE an icon are worth undoing: filling an empty slot is
            // what refresh is for. Collected as the edits happen, because whether an existing
            // icon is replaced depends on the override setting and on the generator's own skips.
            val replaced = mutableListOf<PackageInfoStruct>()
            var withheld = 0
            iconGenService.refreshIcons(targets, opt, modifierOptions) { application, base, rendered, isFallback, sourcePack ->
                val origin = lockManager.resolveOrigin(application.key, sourcePack) ?: sourcePack
                if (rendered != null && origin in lockedOrigins) {
                    // An own pack used as source handed out an icon whose real origin isn't
                    // owned on this device — withhold it (the slot stays empty).
                    withheld++
                } else {
                    val live = applicationList.firstOrNull { it.key == application.key }
                    if (live?.createdIcon != null) replaced += live
                    editApplication(application, application.changeExport(rendered, isFallback, origin, isRefreshMade = true, isCustom = false, isLegacy = false, baseIcon = base))
                }
            }
            captureUndoRows(replaced, persisted = false)
            withheld
        }
    }

    enum class WatchIconApplyResult {
        APPLIED,
        LOCKED,
        TARGET_GONE,
        PROFILE_CHANGED
    }

    /**
     * Applies a watched icon only to the profile that opened its suggestion. Resolving the true
     * source, rendering and editing the live row stay under one profile operation so a second
     * notification cannot switch sessions between those steps.
     */
    suspend fun applyWatchIcon(
        expectedProfileId: Long,
        application: PackageInfoStruct,
        icon: IconPackDrawable,
        sourcePackName: String?
    ): WatchIconApplyResult = withContext(Dispatchers.Default) {
        profileOperations.run {
            if (activeProfileId != expectedProfileId) return@run WatchIconApplyResult.PROFILE_CHANGED
            val preferences = context.dataStore.getPreferencesAfterPendingWrites()
            val (origin, locked) = lockManager.resolvePickedSource(application.key, sourcePackName)
            if (locked) return@run WatchIconApplyResult.LOCKED
            val rendered = renderCustomIcon(icon, preferences)
            val index = applicationList.indexOfFirst { it.key == application.key }
            if (index < 0) return@run WatchIconApplyResult.TARGET_GONE
            applicationList[index].let { live ->
                editApplication(
                    index,
                    live.changeExport(
                        rendered,
                        sourcePackName = origin,
                        isRefreshMade = false,
                        isCustom = true,
                        isLegacy = false,
                        baseIcon = icon
                    )
                )
            }
            WatchIconApplyResult.APPLIED
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

    suspend fun renderCustomIcon(base: IconPackDrawable, preferences: Preferences): IconPackDrawable =
        renderGlobal(base, preferences, preferences.getBooleanValue(GlobalApplyCustomKey))

    /**
     * Re-renders enabled categories from each icon's immutable base. Disabled categories stay
     * untouched; selecting a category with no visible modifier explicitly restores its base.
     */
    suspend fun applyGlobalModifiers(
        preferences: Preferences,
        modifierOptions: GenerationOptions,
        applyGenerated: Boolean,
        applyExisting: Boolean,
        applyCustom: Boolean,
        includeEmpty: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.Default) {
        profileOperations.run {
            val store = context.dataStore
            val previousPreferences = store.data.first()
            var preferencesPersisted = false
            val original = applicationList.toList()

            suspend fun rollbackPreferences(error: Throwable) {
                if (!preferencesPersisted) return
                withContext(NonCancellable) {
                    runCatching { store.persistGlobalModifierPrefs(previousPreferences) }
                        .onFailure(error::addSuppressed)
                }
            }

            try {
                // Preferences and icons belong to the same profile operation. Keeping both under
                // this lock prevents a switch from pairing one profile's recipe with another's icons.
                store.persistGlobalModifierPrefs(preferences)
                preferencesPersisted = true
                val targets = original.filter { app ->
                    app.key !in lockManager.lockedKeys && shouldProcessGlobalLayer(
                        hasIcon = app.createdIcon != null,
                        isCustom = app.isCustom,
                        isRefreshMade = app.isRefreshMade,
                        applyGenerated = applyGenerated,
                        applyExisting = applyExisting,
                        applyCustom = applyCustom,
                        includeEmpty = includeEmpty
                    )
                }
                var done = 0
                val renderedByKey = mutableMapOf<String, PackageInfoStruct>()
                onProgress(0, targets.size)
                for (app in targets) {
                    val base = app.baseIcon ?: app.createdIcon
                    if (base != null) {
                        val apply = shouldApplyGlobalLayer(
                            app.isCustom, app.isRefreshMade,
                            applyGenerated, applyExisting, applyCustom
                        )
                        val rendered = if (apply && modifierOptions.hasVisibleModifierEffect()) {
                            iconGenService.applyModifier(base, modifierOptions)
                        } else base
                        renderedByKey[app.key] = app.changeRenderedIcon(rendered)
                    } else {
                        generateEmptyIconResult(app, preferences, modifierOptions)?.let {
                            renderedByKey[app.key] = it
                        }
                    }
                    done++
                    onProgress(done, targets.size)
                }
                val updated = mergeGlobalRenders(original, renderedByKey)
                replaceApplications(updated)
                persistActiveProfileIcons(unbuiltAfter = true)
            } catch (e: CancellationException) {
                replaceApplications(original)
                rollbackPreferences(e)
                throw e
            } catch (e: Exception) {
                replaceApplications(original)
                rollbackPreferences(e)
                throw e
            }
        }
    }

    private suspend fun generateEmptyIconResult(
        application: PackageInfoStruct,
        preferences: Preferences,
        modifierOptions: GenerationOptions
    ): PackageInfoStruct? {
        val sourceOptions = GenerationOptions.fromPreferences(preferences, context, override = true)
        val lockedOrigins = lockManager.lockedOriginsFor(sourceOptions)
        var result: PackageInfoStruct? = null
        iconGenService.refreshIcon(application, sourceOptions, modifierOptions) { app, base, rendered, sourcePack ->
            val origin = lockManager.resolveOrigin(app.key, sourcePack)
            if (rendered == null || origin == null || origin !in lockedOrigins) {
                result = app.changeExport(rendered, sourcePackName = origin, isRefreshMade = true, isCustom = false, isLegacy = false, baseIcon = base)
            }
        }
        return result
    }

    private fun modifierFor(preferences: Preferences, apply: Boolean): GenerationOptions? {
        if (!apply) return null
        return globalModifierOptions(preferences).takeIf { it.hasVisibleModifierEffect() }
    }

    suspend fun buildAndSignIconPack(
        profileId: Long,
        preferences: Preferences,
        textMethod: (text: String) -> Unit,
        progressMethod: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): BuiltIconPack =
        withContext(Dispatchers.Default) {
            profileOperations.run {
                check(activeProfileId == profileId) { "Profile changed before icon pack build" }
                val profileApps = applicationList.toList()
                val preservedRows = lockManager.preservedRows().toList()
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
                val perAppSelections = profileApps
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
                    profileApps.mapNotNull {
                        it.sourcePackName?.takeIf { source -> source.isNotEmpty() }
                    }.toSet()
                )
                val buildApps = if (lockedSources.isEmpty()) profileApps
                    else profileApps.map { app ->
                        if (app.sourcePackName in lockedSources) app.changeExport(null) else app
                    }

                // Each profile builds its own pack: a per-profile package name (side-by-side
                // installs) and the user's chosen launcher label.
                val profile = packRepo.profile(profileId)
                val packLabel = profile?.packLabel?.ifEmpty { profile.name } ?: "Renkin Pack"
                val iconPackGenerator = IconPackBuilder(
                    context,
                    buildApps,
                    calendarData.mappings,
                    calendarData.drawables,
                    packPackageName = profileManager.packPackageNameFor(profileId),
                    packLabel = packLabel
                )
                val canBeInstalled = iconPackGenerator.canBeInstalled() // must be called before build and sign
                val apk = iconPackGenerator.buildAndSign(
                    themed,
                    iconColor.toHexString(),
                    bgColor.toHexString(),
                    textMethod,
                    progressMethod
                )

                BuiltIconPack(
                    uri = apk,
                    packageName = iconPackGenerator.getIconPackName(),
                    canBeInstalled = canBeInstalled,
                    profileId = profileId,
                    packLabel = packLabel,
                    preferences = preferences,
                    profileApps = profileApps,
                    preservedRows = preservedRows
                )
            }
        }

    suspend fun installIconPack(iconPack: BuiltIconPack): ApkInstallResult = withContext(Dispatchers.Default) {
        val result = installOrReportConflict(iconPack.canBeInstalled) {
            ApkInstaller(context).install(iconPack.uri)
        }
        finishInstallAttempt(iconPack, result)
        result
    }

    /** Explicitly approved fallback after an update conflict: uninstall, then install the APK. */
    suspend fun replaceIconPack(iconPack: BuiltIconPack): ApkInstallResult = withContext(Dispatchers.Default) {
        val result = replaceAfterConflict(
            uninstall = { ApkUninstaller(context).uninstall(iconPack.packageName) },
            install = { ApkInstaller(context).install(iconPack.uri) }
        )
        finishInstallAttempt(iconPack, result)
        result
    }

    private suspend fun finishInstallAttempt(iconPack: BuiltIconPack, result: ApkInstallResult) {
        val success = result == ApkInstallResult.SUCCESS
        // Building the APK is the commitment, not installing it: someone who only wants their
        // icons stored can go through Build and dismiss the installer. Either way the save
        // matches the pack that was produced, so it counts as built.
        profileOperations.run {
            persistProfileIcons(
                profileId = iconPack.profileId,
                apps = iconPack.profileApps,
                preservedRows = iconPack.preservedRows,
                unbuiltAfter = false
            )
            renkinPackStore.recordBuilt(iconPack.profileId)
            if (success) profileManager.recordBuiltPrimary(iconPack.profileId, iconPack.preferences)
        }

        // The just-(re)installed pack carries a fresh provenance map.
        if (success) lockManager.clearProvenanceCache()
    }

    /**
     * Persists the active profile's icons without building (offered before switching away).
     * Locks the refresh output like a build does and marks the profile as saved-but-not-built.
     */
    suspend fun saveActiveProfileIcons() = withContext(Dispatchers.Default) {
        profileOperations.run { persistActiveProfileIcons(unbuiltAfter = true) }
    }

    /**
     * Saves the active profile's icons and locks the refresh-made ones in (from now on a
     * refresh replaces none of them — the user clears icons or hand-edits to change them).
     * [unbuiltAfter] records whether this save is still waiting for a build.
     */
    private suspend fun persistActiveProfileIcons(unbuiltAfter: Boolean) {
        persistProfileIcons(
            profileId = activeProfileId,
            apps = applicationList.toList(),
            preservedRows = lockManager.preservedRows().toList(),
            unbuiltAfter = unbuiltAfter
        )
    }

    /** Saves a completed operation back to the profile that started it, never the current one. */
    private suspend fun persistProfileIcons(
        profileId: Long,
        apps: List<PackageInfoStruct>,
        preservedRows: Collection<DbApplication>,
        unbuiltAfter: Boolean
    ) {
        // A profile can be deleted through another UI path while the system installer owns the
        // screen. Do not recreate icon rows that no longer have an owning profile.
        if (!profileManager.profileExists(profileId)) return
        renkinPackStore.save(profileId, apps, preservedRows)
        profileManager.markUnbuilt(profileId, unbuiltAfter)

        // Only the matching live session may be mutated. An inactive profile will load these
        // persisted rows as locked when the user returns to it.
        if (activeProfileId != profileId) return
        val replacedKeys = apps.filter { it.createdIcon != null }.map { it.key }.toSet()
        lockManager.releaseReplaced(replacedKeys)
        for (app in applicationList.toList()) {
            if (app.isRefreshMade) editApplication(app, app.locked())
        }
    }

    private suspend fun loadRenkinPack(profileId: Long) {
        lockManager.clear()
        val prefs = context.dataStore.data.first()
        // Saved vectors may contain theme references. Resolve their fallback from the user's
        // explicit theme choice every time a profile is loaded, not from stale system state.
        val defaultColor = prefs.getDefaultIconColor(context)
        val saved = renkinPackStore.load(profileId, defaultColor)
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
            // Rows for apps not installed here, and rows whose artwork will not decode: both are
            // invisible to the list, and both are written back verbatim by the next save.
            if (key !in appKeys || entry.decodeFailed) lockManager.holdOrphan(key, entry.row)
        }

        for (app in applicationList.toList()) {
            val entry = saved[app.key] ?: continue
            // Undecodable artwork: keep the row (held above), show the app as having no icon.
            if (entry.decodeFailed) continue
            if (entry.sourcePackName != null && entry.sourcePackName in lockedPacks) {
                // Held back: invisible to the list and the build, preserved by saves,
                // loaded normally once the pack is installed (or verified free).
                lockManager.lock(app.key, entry.row)
                continue
            }
            // A reference row (no image data, source pack known) whose pack is usable:
            // rebuild the icon from the pack. Failure keeps the row held back for retry.
            val isReference = entry.icon == null && entry.row.drawable.isEmpty() && entry.sourcePackName != null
            val storedIcon = if (isReference) {
                val rebuilt = materializeReference(app, entry, prefs)
                if (rebuilt == null) {
                    lockManager.lock(app.key, entry.row)
                    continue
                }
                rebuilt
            } else entry.icon ?: entry.baseIcon
            val updated = when {
                // Loaded from the DB = built/saved before, so it arrives locked (isRefreshMade
                // defaults to false): a refresh won't replace it.
                storedIcon != null -> {
                    val baseIcon = entry.baseIcon ?: storedIcon
                    val rendered = if (isReference) {
                        renderGlobal(
                            storedIcon,
                            prefs,
                            if (entry.isCustom) {
                                prefs.getBooleanValue(GlobalApplyCustomKey)
                            } else {
                                prefs.getBooleanValue(GlobalApplyExistingKey)
                            }
                        )
                    } else storedIcon
                    app.changeExport(
                        rendered,
                        isFallback = entry.isFallback,
                        sourcePackName = entry.sourcePackName,
                        isCustom = entry.isCustom,
                        isLegacy = entry.isLegacy,
                        baseIcon = baseIcon,
                        sourceUrl = entry.sourceUrl
                    ).changeCalendar(entry.calendarEnabled, entry.calendarPrefix, entry.calendarPackName)
                }
                else -> app.changeCalendar(entry.calendarEnabled, entry.calendarPrefix, entry.calendarPackName)
            }
            editApplication(app, updated)
        }
        lockManager.publish()
    }

    private suspend fun renderGlobal(
        base: IconPackDrawable,
        preferences: Preferences,
        apply: Boolean
    ): IconPackDrawable {
        val options = modifierFor(preferences, apply) ?: return base
        return runCatching { iconGenService.applyModifier(base, options) }.getOrDefault(base)
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
            iconPackRepo.load(installedApplicationRefs())
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
            val isReference = entry.icon == null && entry.row.drawable.isEmpty() && entry.sourcePackName != null
            val storedIcon = entry.icon ?: materializeReference(app, entry, prefs) ?: continue
            val baseIcon = entry.baseIcon ?: storedIcon
            val rendered = if (isReference) {
                renderGlobal(
                    storedIcon,
                    prefs,
                    if (entry.isCustom) {
                        prefs.getBooleanValue(GlobalApplyCustomKey)
                    } else {
                        prefs.getBooleanValue(GlobalApplyExistingKey)
                    }
                )
            } else storedIcon
            editApplication(
                app,
                app.changeExport(
                    rendered,
                    isFallback = entry.isFallback,
                    sourcePackName = entry.sourcePackName,
                    isCustom = entry.isCustom,
                    isLegacy = entry.isLegacy,
                    baseIcon = baseIcon,
                    sourceUrl = entry.sourceUrl
                )
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

    // Only the last change is undoable; a second one replaces it, exactly like the snackbar it
    // is offered through.
    private val undoTracker = IconUndoTracker()

    /** How many icons the pending undo would put back; 0 when there is nothing to offer. */
    val undoableIconCount: Int get() = undoTracker.size

    /** Records the rows a change is about to overwrite, so it can go back. */
    fun captureUndoRows(rows: List<PackageInfoStruct>, persisted: Boolean) {
        undoTracker.capture(rows, activeProfileId, persisted)
    }

    /** Drops the offer — after a build, a profile switch or anything that invalidates the rows. */
    fun clearUndo() = undoTracker.clear()

    /**
     * Puts the captured rows back. Returns false when the step no longer applies: another
     * profile is active, or the apps it described are gone.
     */
    suspend fun undoLastIconChange(): Boolean = withContext(Dispatchers.Default) {
        profileOperations.run {
            val persisted = undoTracker.step?.persisted ?: return@run false
            val restoration = undoTracker.restorationFor(applicationList, activeProfileId)
            if (restoration.isEmpty()) return@run false
            for ((index, row) in restoration) editApplication(index, row)
            // A change that reached the database has to be written back, or the restored state
            // would only live until the next load.
            if (persisted) saveRenkinPack()
            undoTracker.clear()
            true
        }
    }

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
        // Everything is going; an undo pointing at rows this wipes would restore a mixture.
        clearUndo()
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
    suspend fun getSavedPackKeys(profileId: Long = activeProfileId): Set<String> =
        renkinPackStore.savedKeys(profileId)

    /** Fingerprints of the stored icons — the "saved" half of the changes-since-build compare. */
    suspend fun getSavedIconHashes(profileId: Long = activeProfileId): Map<String, String> =
        renkinPackStore.savedHashes(profileId)

    /**
     * Fingerprints of what the last build shipped; empty when this profile never built. A profile
     * that built before this record existed adopts its stored icons instead, so upgrading the app
     * never reports an already-built pack as entirely new.
     */
    suspend fun getBuiltIconHashes(profileId: Long = activeProfileId): Map<String, String> {
        if (profileManager.profile(profileId)?.hasUnbuiltChanges == false) {
            renkinPackStore.adoptBuiltIfMissing(profileId)
        }
        return renkinPackStore.builtHashes(profileId)
    }

    /** Records the just-built pack's contents, so later saves can be told apart from it. */
    suspend fun recordBuiltIcons(profileId: Long) = renkinPackStore.recordBuilt(profileId)

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
    suspend fun deleteProfile(id: Long) = withContext(Dispatchers.Default) {
        if (id == DEFAULT_PROFILE_ID) return@withContext
        ensureInitialized()
        profileOperations.run {
            isProfileSwitching = true
            try {
                if (id == activeProfileId) switchProfileLocked(DEFAULT_PROFILE_ID)
                profileManager.deleteProfile(id)
            } finally {
                isProfileSwitching = false
            }
        }
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
        ensureInitialized()
        profileOperations.run {
            isProfileSwitching = true
            try {
                switchProfileLocked(newProfileId)
            } finally {
                isProfileSwitching = false
            }
        }
    }

    /** Runs only while [profileOperations] is held. */
    private suspend fun switchProfileLocked(newProfileId: Long) {
        if (!profileManager.switchTo(newProfileId)) return
        // The captured rows belong to the profile being left; they must not be restorable here.
        clearUndo()
        // Reset the in-memory icons and load exactly the target selected above.
        resetInMemoryIcons()
        loadRenkinPack(newProfileId)
    }

    /**
     * Re-reads the active profile id and its saved icons after a backup import replaced the
     * stores under the running app. Same in-memory reset as a profile switch, minus the
     * snapshot of the leaving state — that state was just overwritten on purpose.
     */
    suspend fun reloadActiveProfile() = withContext(Dispatchers.Default) {
        profileOperations.run {
            isProfileSwitching = true
            try {
                profileManager.reloadActiveId()
                val profileId = activeProfileId
                resetInMemoryIcons()
                loadRenkinPack(profileId)
            } finally {
                isProfileSwitching = false
            }
        }
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
        val packRows = (installed.keys + counts.keys).distinct().map { pack ->
            PackUsage(
                packageName = pack,
                label = installed[pack]?.applicationName
                    ?: cachedLabels[pack]?.label?.ifEmpty { null }
                    ?: pack,
                count = counts[pack] ?: 0,
                installed = pack in installed
            )
        }
        // Online FOSS libraries count like packs — attributed by the stored source URL
        // (curated sets by name, community repos as "owner/repo"). "installed" on purpose:
        // there is nothing to install, so no missing-pack scare.
        val onlineRows = applicationList
            .mapNotNull { it.sourceUrl?.let(::onlineAttributionLabel) }
            .groupingBy { it }
            .eachCount()
            .map { (label, count) ->
                PackUsage("online:$label", label, count, installed = true)
            }
        (packRows + onlineRows)
            .sortedWith(compareByDescending<PackUsage> { it.count }.thenBy { it.label.lowercase() })
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

    /** How many installed apps each pack declares an icon for (see IconPackRepository). */
    fun packMatchedAppCounts(): Map<String, Int> = iconPackRepo.matchedAppCounts()

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
        val canBeInstalled: Boolean,
        val profileId: Long,
        val packLabel: String,
        val preferences: Preferences,
        val profileApps: List<PackageInfoStruct>,
        val preservedRows: List<DbApplication>
    )
}
