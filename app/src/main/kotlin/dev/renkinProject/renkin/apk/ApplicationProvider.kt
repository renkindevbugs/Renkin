package dev.renkinProject.renkin.apk

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.BuiltPrimarySourceKey
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.PrimaryIconPackKey
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.OverrideIconKey
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.StorageOperationBusyException
import dev.renkinProject.renkin.data.FallbackSource
import dev.renkinProject.renkin.data.GlobalApplyCustomKey
import dev.renkinProject.renkin.data.GlobalApplyExistingKey
import dev.renkinProject.renkin.data.GlobalApplyGeneratedKey
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.getDefaultIconColor
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.data.persistGlobalStylePrefs
import dev.renkinProject.renkin.data.setPrimarySource
import dev.renkinProject.renkin.data.restoreBuiltPrimarySource
import dev.renkinProject.renkin.data.online.onlineAttributionLabel
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.withSource
import dev.renkinProject.renkin.icon.creator.withModifiers
import dev.renkinProject.renkin.icon.creator.withExport
import dev.renkinProject.renkin.icon.creator.withEffect
import dev.renkinProject.renkin.icon.creator.PackBrowserPreviews
import dev.renkinProject.renkin.icon.creator.globalModifierOptions
import dev.renkinProject.renkin.icon.creator.hasVisibleModifierEffect
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.InstalledAppCatalog
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.packages.supportDynamicColors
import dev.renkinProject.renkin.dataStore
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

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

/** Serializes active-profile mutations and coordinated snapshots. */
internal class ProfileOperationGate {
    private val mutex = Mutex()

    suspend fun <T> run(operation: suspend () -> T): T = StorageOperationGate.run {
        mutex.withLock { operation() }
    }

    suspend fun <T> runWithBoundedWait(operation: suspend () -> T): T {
        try {
            return StorageOperationGate.runWithBoundedWait { runWithBoundedProfileWait(operation) }
        } catch (_: StorageOperationBusyException) {
            throw ProfileOperationBusyException()
        }
    }

    private suspend fun <T> runWithBoundedProfileWait(operation: suspend () -> T): T {
        var acquired = false
        try {
            // Limit only acquisition: canceling a preference write could leave a partial recipe.
            val available = withTimeoutOrNull(300L) {
                mutex.lock()
                acquired = true
                true
            } == true
            if (!available) throw ProfileOperationBusyException()
            currentCoroutineContext().ensureActive()
            return operation()
        } finally {
            if (acquired) mutex.unlock()
        }
    }
}

/**
 * The domain hub between the UI layer (MainViewModel) and everything icon-related: the live
 * app list, icon generation/refresh, pack-build coordination and the saved-pack store. Profile state
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
    private val iconGenService: IconGenerationService,
    private val iconPackBuildService: IconPackBuildService,
    private val appManager: ApplicationManager,
    private val installedAppCatalog: InstalledAppCatalog
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
    private val renderTracker = IconRenderTracker()
    private val savedProfileRevisions = mutableMapOf<Long, Long>()
    private var storageEpoch = 0L
    private var longOperations by mutableStateOf<Map<Long, ProfileSessionToken>>(emptyMap())
    private var nextLongOperation = 0L
    val longOperationRunning: Boolean get() = longOperations.values.any { it == sessionToken }
    var unsavedIconKeys: Set<String> by mutableStateOf(emptySet())
        private set
    private val providerInstanceId = UUID.randomUUID().toString()
    private var sessionRevision by mutableStateOf(0L)

    val sessionToken: ProfileSessionToken
        get() = ProfileSessionToken(activeProfileId, sessionRevision, providerInstanceId)

    fun isCurrentSession(expected: ProfileSessionToken): Boolean =
        startupComplete && !isProfileSwitching && expected == sessionToken

    private fun requireCurrentSession(expected: ProfileSessionToken) {
        val reason = when {
            expected.providerInstanceId != providerInstanceId -> ProfileSessionFailureReason.PROCESS_LOST
            !startupComplete || isProfileSwitching -> ProfileSessionFailureReason.LOADING
            expected != sessionToken -> ProfileSessionFailureReason.EXPIRED
            else -> return
        }
        throw ProfileSessionChangedException(reason)
    }

    private suspend fun <T> runProfileOperation(
        expected: ProfileSessionToken,
        operation: suspend () -> T
    ): T = profileOperations.run {
        requireCurrentSession(expected)
        currentCoroutineContext().ensureActive()
        operation()
    }

    private fun requireRecipeAvailable(expected: ProfileSessionToken) {
        if (longOperations.values.any { it == expected }) throw ProfileOperationBusyException()
    }

    private suspend fun <T> runLongProfileOperation(
        expected: ProfileSessionToken,
        operation: suspend () -> T
    ): T {
        val id = runProfileOperation(expected) {
            requireRecipeAvailable(expected)
            (++nextLongOperation).also { longOperations = longOperations + (it to expected) }
        }
        try {
            return operation()
        } finally {
            withContext(NonCancellable) {
                profileOperations.run { longOperations = longOperations - id }
            }
        }
    }

    suspend fun commitSessionState(expected: ProfileSessionToken, update: () -> Unit): Boolean =
        profileOperations.run {
            if (!isCurrentSession(expected)) return@run false
            currentCoroutineContext().ensureActive()
            update()
            true
        }

    private fun invalidateSession() {
        sessionRevision++
        renderTracker.clear()
        clearUndo()
    }

    /** True while the shared preferences and live icon list are changing profiles. */
    var isProfileSwitching: Boolean by mutableStateOf(false)
        private set

    /** Saved colours/gradients, shared by every profile. Read straight from the database flow. */
    fun colorPresets(): kotlinx.coroutines.flow.Flow<List<dev.renkinProject.renkin.data.ColorPreset>> =
        packRepo.colorPresetsFlow()

    suspend fun saveColorPreset(name: String, style: String) {
        packRepo.saveColorPreset(name, style)
    }

    suspend fun deleteColorPreset(id: Long) {
        packRepo.deleteColorPreset(id)
    }

    /** Saved Modifier-tab recipes, shared by every profile like the colour library. */
    fun modifierPresets(): kotlinx.coroutines.flow.Flow<List<dev.renkinProject.renkin.data.ModifierPreset>> =
        packRepo.modifierPresetsFlow()

    suspend fun saveModifierPreset(name: String, payload: String, schemaVersion: Int) {
        packRepo.saveModifierPreset(name, payload, schemaVersion)
    }

    suspend fun renameModifierPreset(id: Long, name: String) {
        packRepo.renameModifierPreset(id, name)
    }

    suspend fun updateModifierPreset(id: Long, payload: String, schemaVersion: Int) {
        packRepo.updateModifierPreset(id, payload, schemaVersion)
    }

    suspend fun markModifierPresetUsed(id: Long) {
        packRepo.markModifierPresetUsed(id)
    }

    suspend fun deleteModifierPreset(id: Long) {
        packRepo.deleteModifierPreset(id)
    }

    private suspend fun initialize() {
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
    suspend fun ensureInitialized() = StorageOperationGate.run {
        initialLoadMutex.withLock {
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
    }

    /**
     * Re-discovers apps/packs and reloads persisted profile state without discarding the keys
     * edited in this session. Nothing is written to storage: a process restart still returns to
     * the last build/save. If reloading fails after clearing the list, restore the old session.
     */
    suspend fun reloadPreservingSession(
        expected: ProfileSessionToken,
        preserveKeys: Set<String>
    ): ProfileSessionToken = withContext(Dispatchers.Default) {
        runProfileOperation(expected) {
            val unsaved = unsavedIconKeys
            val keptKeys = preserveKeys + unsaved
            invalidateSession()
            val session = applicationList.toList()
            isProfileSwitching = true
            try {
                initialize()
                replaceApplications(mergeApplicationReload(session, applicationList.toList(), keptKeys))
                val liveKeys = applicationList.mapTo(mutableSetOf()) { it.key }
                unsavedIconKeys = keptKeys.filterTo(mutableSetOf()) { it in liveKeys }
                sessionToken
            } catch (error: Exception) {
                replaceApplications(session)
                unsavedIconKeys = unsaved
                if (error is CancellationException) throw error
                throw ProfileReloadFailedException(sessionToken, error)
            } finally {
                isProfileSwitching = false
            }
        }
    }

    private suspend fun initializeApplications() = withContext(Dispatchers.Default) {
        val apps = installedAppCatalog.getAllInstalledApps()
        apps.sort()

        replaceApplications(apps.asList())
        applicationsLoaded = true
    }

    private fun replaceApplications(apps: List<PackageInfoStruct>) {
        val previous = applicationList.associateBy { it.key }
        val next = apps.associateBy { it.key }
        val changedKeys = (previous.keys + next.keys).filter { previous[it] !== next[it] }
        changedKeys.forEach { key ->
            renderTracker.changed(key)
            undoTracker.discard(key)
        }
        val editedKeys = changedKeys.filter { key ->
            val before = previous[key]
            val after = next[key]
            before != null && after != null && !sameIconState(before, after)
        }
        unsavedIconKeys = unsavedIconKeys + editedKeys
        _applicationList.clear()
        _applicationList.addAll(apps)
    }

    private suspend fun initializeIconPacks() {
        iconPackRepo.load(installedApplicationRefs())
        // Every pack seen installed is owned on this device forever — the paid-pack lock
        // never applies to it again, even after an uninstall.
        lockManager.recordInstalledPacks(iconPacks)
    }

    /** The already-loaded app list as lightweight identity refs for appfilter matching. */
    private fun installedApplicationRefs(): List<InstalledApplication> =
        applicationList.map { InstalledApplication(it.packageName, it.activityName, it.iconID) }

    private suspend fun initializeRenkinPack() {
        profileManager.initActiveId()
        loadRenkinPack(activeProfileId)
        unsavedIconKeys = emptySet()
        startupComplete = true
    }

    private data class RefreshPlan(
        val preferences: Preferences,
        val sourceOptions: GenerationOptions,
        val modifierOptions: GenerationOptions?,
        val targets: List<PackageInfoStruct>,
        val tickets: Map<String, IconRenderTicket>,
        val sources: IconGenerationSources
    )

    private data class RenderedIcon(
        val key: String,
        val base: IconPackDrawable?,
        val rendered: IconPackDrawable?,
        val isFallback: Boolean,
        val sourcePack: String
    )

    data class IconRefreshResult(
        val withheldCount: Int,
        val conflictedCount: Int,
        val missingCount: Int,
        val undoStep: IconUndoStep?
    )

    private suspend fun captureRefreshPlan(application: PackageInfoStruct? = null): RefreshPlan {
        val preferences = context.dataStore.getPreferencesAfterPendingWrites()
        var options = GenerationOptions.fromPreferences(preferences, context, override =
            application != null || preferences.getBooleanValue(OverrideIconKey))
        if (application == null && options.export.themed && supportDynamicColors()) {
            options = options.withExport {
                copy(background = background.copy(color = context.resources.getColor(R.color.icon_background_color, null)))
            }.withModifiers {
                withEffect {
                    copy(colorizer = colorizer.copy(firstColor = context.resources.getColor(R.color.icon_color, null)))
                }
            }
        }
        val targets = applicationList.filter { app ->
            (application == null || app.key == application.key) &&
                options.source.primarySource != Source.NONE &&
                (options.override || app.createdIcon == null || app.isRefreshMade) &&
                (options.override || app.key !in lockManager.lockedKeys)
        }
        return RefreshPlan(
            preferences, options,
            modifierFor(preferences, apply = preferences.getBooleanValue(GlobalApplyGeneratedKey, true)),
            if (application == null) targets.map { it.changeExport(null) } else targets,
            targets.associate { it.key to renderTracker.reserve(it.key) },
            iconGenService.captureRefreshSources(options)
        )
    }

    private suspend fun requireUnchangedRecipe(preferences: Preferences) {
        if (!sameRenderPreferences(preferences, context.dataStore.getPreferencesAfterPendingWrites(), context)) {
            throw ProfileOperationSupersededException()
        }
    }

    suspend fun refreshIcon(expected: ProfileSessionToken, application: PackageInfoStruct): IconUndoStep? =
        withContext(Dispatchers.Default) {
            val plan = runProfileOperation(expected) { captureRefreshPlan(application) }
            val target = plan.targets.firstOrNull() ?: return@withContext null
            var result: RenderedIcon? = null
            iconGenService.refreshIcon(target, plan.sourceOptions, plan.modifierOptions, plan.sources) { app, base, rendered, pack ->
                result = RenderedIcon(app.key, base, rendered, false, pack)
            }
            runProfileOperation(expected) {
                requireUnchangedRecipe(plan.preferences)
                if (!renderTracker.isCurrent(plan.tickets.getValue(target.key))) {
                    throw ProfileOperationSupersededException()
                }
                val generated = result ?: return@runProfileOperation null
                val live = applicationList.firstOrNull { it.key == target.key } ?: return@runProfileOperation null
                val origin = lockManager.resolveOrigin(live.key, generated.sourcePack)
                if (generated.rendered != null && origin in lockManager.lockedOriginsFor(plan.sourceOptions)) {
                    return@runProfileOperation null
                }
                val unsavedBefore = unsavedIconKeys
                editApplication(live, live.changeExport(
                    generated.rendered, sourcePackName = origin, isRefreshMade = true,
                    isCustom = false, isLegacy = false, baseIcon = generated.base
                ))
                if (live.createdIcon != null) {
                    captureUndoRows(listOf(live), persisted = false, unsavedBefore = unsavedBefore)
                    undoTracker.step
                } else null
            }
        }

    /**
     * Restores one app to its launcher default: drops an imported row held behind a missing pack
     * and clears calendar rotation. Invalidates pending renders even for an empty slot.
     * The returned step identifies the change whose Undo may be offered.
     */
    suspend fun resetIcon(expected: ProfileSessionToken, application: PackageInfoStruct): IconUndoStep? =
        withContext(Dispatchers.Default) {
            runProfileOperation(expected) {
                val index = applicationList.indexOfFirst { it.key == application.key }
                if (index < 0) return@runProfileOperation null
                val live = applicationList[index]
                val hadSomething = live.createdIcon != null ||
                    live.calendarEnabled ||
                    application.key in lockManager.lockedKeys
                renderTracker.changed(live.key)
                if (!hadSomething) return@runProfileOperation null
                val unsavedBefore = unsavedIconKeys
                lockManager.discard(application.key)
                editApplication(index, live.changeExport(null).changeCalendar(false, null, null))
                captureUndoRows(listOf(live), persisted = true, unsavedBefore = unsavedBefore)
                undoTracker.step
            }
        }

    suspend fun refreshIcons(
        expected: ProfileSessionToken,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): IconRefreshResult = withContext(Dispatchers.Default) {
        runLongProfileOperation(expected) {
            val plan = runProfileOperation(expected) { captureRefreshPlan() }
            val replaced = mutableMapOf<String, Pair<PackageInfoStruct, PackageInfoStruct>>()
            val previouslyUnsaved = mutableSetOf<String>()
            var withheld = 0
            var conflicted = 0
            var missing = 0
            iconGenService.refreshIcons(
                plan.targets, plan.sourceOptions, plan.modifierOptions, plan.sources, onProgress
            ) { results ->
                runProfileOperation(expected) {
                    requireUnchangedRecipe(plan.preferences)
                    val lockedOrigins = lockManager.lockedOriginsFor(plan.sourceOptions)
                    val indexByKey = applicationList.withIndex().associate { (index, app) -> app.key to index }
                    for (result in results) {
                        val index = indexByKey[result.key]
                        if (index == null) {
                            missing++
                            continue
                        }
                        if (!renderTracker.isCurrent(plan.tickets.getValue(result.key))) {
                            conflicted++
                            continue
                        }
                        val live = applicationList[index]
                        val origin = lockManager.resolveOrigin(live.key, result.sourcePack) ?: result.sourcePack
                        if (result.rendered != null && origin in lockedOrigins) {
                            withheld++
                            continue
                        }
                        val updated = live.changeExport(
                            result.rendered, result.isFallback, origin, isRefreshMade = true,
                            isCustom = false, isLegacy = false, baseIcon = result.base
                        )
                        if (live.createdIcon != null) {
                            replaced[live.key] = live to updated
                            if (live.key in unsavedIconKeys) previouslyUnsaved += live.key
                        }
                        editApplication(index, updated)
                    }
                }
            }
            runProfileOperation(expected) {
                val liveByKey = applicationList.associateBy { it.key }
                val undoRows = replaced.values.filter { (_, applied) -> liveByKey[applied.key] === applied }
                    .map { (before, _) -> before }
                val undo = if (undoRows.isNotEmpty()) {
                    captureUndoRows(undoRows, persisted = false, unsavedBefore = previouslyUnsaved)
                    undoTracker.step
                } else null
                IconRefreshResult(withheld, conflicted, missing, undo)
            }
        }
    }

    fun ownsUndo(step: IconUndoStep): Boolean = undoTracker.step?.operationId == step.operationId

    suspend fun applyWatchIcon(
        expected: ProfileSessionToken,
        application: PackageInfoStruct,
        icon: IconPackDrawable,
        sourcePackName: String?
    ): IconApplyOutcome = applyPickedIcon(expected, application, icon, sourcePackName) { live, origin, rendered ->
        live.changeExport(
            rendered, sourcePackName = origin, isRefreshMade = false,
            isCustom = true, isLegacy = false, baseIcon = icon
        )
    }

    private data class PickedIconPlan(val preferences: Preferences, val ticket: IconRenderTicket)

    suspend fun applyPickedIcon(
        expected: ProfileSessionToken,
        application: PackageInfoStruct,
        icon: IconPackDrawable?,
        sourcePackName: String?,
        edit: (PackageInfoStruct, String?, IconPackDrawable?) -> PackageInfoStruct
    ): IconApplyOutcome = withContext(Dispatchers.Default) {
        var rejection = IconApplyOutcome.PROFILE_CHANGED
        val plan = profileOperations.run {
            if (!isCurrentSession(expected)) return@run null
            currentCoroutineContext().ensureActive()
            val (_, locked) = lockManager.resolvePickedSource(application.key, if (icon == null) null else sourcePackName)
            if (icon != null && locked) {
                rejection = IconApplyOutcome.LOCKED
                return@run null
            }
            if (applicationList.none { it.key == application.key }) {
                rejection = IconApplyOutcome.TARGET_GONE
                return@run null
            }
            PickedIconPlan(context.dataStore.getPreferencesAfterPendingWrites(), renderTracker.reserve(application.key))
        } ?: return@withContext rejection
        val rendered = icon?.let { renderCustomIcon(it, plan.preferences) }
        profileOperations.run {
            if (!isCurrentSession(expected)) return@run IconApplyOutcome.PROFILE_CHANGED
            currentCoroutineContext().ensureActive()
            val index = applicationList.indexOfFirst { it.key == application.key }
            if (index < 0) return@run IconApplyOutcome.TARGET_GONE
            if (!renderTracker.isCurrent(plan.ticket) ||
                !sameCustomRenderPreferences(plan.preferences, context.dataStore.getPreferencesAfterPendingWrites())) {
                return@run IconApplyOutcome.CONFLICT
            }
            val (origin, locked) = lockManager.resolvePickedSource(application.key, if (icon == null) null else sourcePackName)
            if (icon != null && locked) return@run IconApplyOutcome.LOCKED
            val updated = edit(applicationList[index], origin, rendered)
            require(updated.key == application.key)
            editApplication(index, updated)
            IconApplyOutcome.APPLIED
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

    private data class EmptyIconSources(
        val generation: IconGenerationSources,
        val origins: Map<String, Map<String, String?>>,
        val lockedOrigins: Set<String>
    )

    private suspend fun captureEmptyIconSources(
        applications: List<PackageInfoStruct>, options: GenerationOptions
    ): EmptyIconSources {
        val packs = listOf(options.source.primaryIconPack, options.source.secondaryIconPack, "").distinct()
        return EmptyIconSources(
            iconGenService.captureRefreshSources(options),
            applications.associate { app ->
                app.key to packs.associateWith { lockManager.resolveOrigin(app.key, it) }
            },
            lockManager.lockedOriginsFor(options)
        )
    }

    private data class GlobalRenderPlan(
        val previousPreferences: Preferences,
        val original: List<PackageInfoStruct>,
        val targets: List<PackageInfoStruct>,
        val tickets: List<IconRenderTicket>,
        val sourceOptions: GenerationOptions,
        val emptySources: EmptyIconSources
    )

    suspend fun applyGlobalModifiers(
        expected: ProfileSessionToken,
        preferences: Preferences,
        modifierOptions: GenerationOptions,
        applyGenerated: Boolean,
        applyExisting: Boolean,
        applyCustom: Boolean,
        includeEmpty: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.Default) {
        runLongProfileOperation(expected) {
            val plan = runProfileOperation(expected) {
                val original = applicationList.toList()
                val targets = original.filter { app ->
                    app.key !in lockManager.lockedKeys && shouldProcessGlobalLayer(
                        app.createdIcon != null, app.isCustom, app.isRefreshMade,
                        applyGenerated, applyExisting, applyCustom, includeEmpty
                    )
                }
                val sourceOptions = GenerationOptions.fromPreferences(preferences, context, override = true)
                GlobalRenderPlan(
                    context.dataStore.getPreferencesAfterPendingWrites(), original, targets,
                    original.map { renderTracker.observe(it.key) }, sourceOptions,
                    captureEmptyIconSources(targets.filter { it.baseIcon == null && it.createdIcon == null }, sourceOptions)
                )
            }
            val renderedByKey = mutableMapOf<String, PackageInfoStruct>()
            onProgress(0, plan.targets.size)
            for ((index, app) in plan.targets.withIndex()) {
                currentCoroutineContext().ensureActive()
                val base = app.baseIcon ?: app.createdIcon
                if (base != null) {
                    val apply = shouldApplyGlobalLayer(
                        app.isCustom, app.isRefreshMade, applyGenerated, applyExisting, applyCustom
                    )
                    val rendered = if (apply && modifierOptions.hasVisibleModifierEffect()) {
                        iconGenService.applyModifier(base, modifierOptions)
                    } else base
                    renderedByKey[app.key] = app.changeRenderedIcon(rendered)
                } else {
                    generateEmptyIcon(app, plan.sourceOptions, modifierOptions, plan.emptySources)?.let { generated ->
                        renderedByKey[app.key] = app.changeExport(
                            generated.rendered, sourcePackName = generated.origin,
                            isRefreshMade = true, isCustom = false, isLegacy = false, baseIcon = generated.base
                        )
                    }
                }
                onProgress(index + 1, plan.targets.size)
            }
            runProfileOperation(expected) {
                if (!sameProfilePreferences(plan.previousPreferences, context.dataStore.getPreferencesAfterPendingWrites())) {
                    throw ProfileOperationSupersededException()
                }
                if (plan.tickets.any { !renderTracker.isCurrent(it) } ||
                    applicationList.map { it.key } != plan.original.map { it.key }) {
                    throw ProfileOperationSupersededException()
                }
                currentCoroutineContext().ensureActive()
                // Once persistence starts, cancellation must not separate the recipe from its icons.
                withContext(NonCancellable) {
                    val previousUnsaved = unsavedIconKeys
                    var preferencesPersisted = false
                    try {
                        context.dataStore.persistGlobalStylePrefs(preferences)
                        preferencesPersisted = true
                        replaceApplications(mergeGlobalRenders(plan.original, renderedByKey))
                        persistActiveProfileIcons(unbuiltAfter = true)
                    } catch (error: Exception) {
                        replaceApplications(plan.original)
                        unsavedIconKeys = previousUnsaved
                        if (preferencesPersisted) {
                            runCatching { context.dataStore.persistGlobalStylePrefs(plan.previousPreferences) }
                                .onFailure(error::addSuppressed)
                        }
                        throw error
                    }
                }
            }
        }
    }

    suspend fun previewEmptyIcon(
        application: PackageInfoStruct,
        sourceOptions: GenerationOptions,
        modifierOptions: GenerationOptions?
    ): IconPackDrawable? = withContext(Dispatchers.Default) {
        val expected = sessionToken
        val sources = runProfileOperation(expected) { captureEmptyIconSources(listOf(application), sourceOptions) }
        generateEmptyIcon(application, sourceOptions, modifierOptions, sources)?.rendered
    }

    private data class EmptyIconGeneration(
        val base: IconPackDrawable?,
        val rendered: IconPackDrawable,
        val origin: String?
    )

    private suspend fun generateEmptyIcon(
        application: PackageInfoStruct,
        sourceOptions: GenerationOptions,
        modifierOptions: GenerationOptions?,
        sources: EmptyIconSources
    ): EmptyIconGeneration? {
        var result: EmptyIconGeneration? = null
        iconGenService.refreshIcon(application, sourceOptions, modifierOptions, sources.generation) { app, base, rendered, pack ->
            val origin = sources.origins[app.key]?.get(pack) ?: pack
            if (rendered != null && origin !in sources.lockedOrigins) {
                result = EmptyIconGeneration(base, rendered, origin)
            }
        }
        if (result == null) {
            currentCoroutineContext().ensureActive()
            val bitmap = application.icon.toSafeBitmapOrNull() ?: return null
            val base = BitmapIconDrawable(bitmap, exportAsAdaptiveIcon = false)
            val rendered = modifierOptions?.let { iconGenService.applyModifier(base, it) } ?: base
            result = EmptyIconGeneration(base, rendered, origin = null)
        }
        return result
    }

    private fun modifierFor(preferences: Preferences, apply: Boolean): GenerationOptions? {
        if (!apply) return null
        return globalModifierOptions(preferences).takeIf { it.hasVisibleModifierEffect() }
    }

    private data class BuildPlan(
        val prepared: IconPackBuildService.PreparedIconPack,
        val undoStep: IconUndoStep?,
        val storageRevision: Long,
        val storageEpoch: Long
    )

    suspend fun buildAndSignIconPack(
        expected: ProfileSessionToken,
        preferences: Preferences,
        textMethod: (text: String) -> Unit,
        progressMethod: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): BuiltIconPack = runLongProfileOperation(expected) {
        val plan = runProfileOperation(expected) {
            if (!sameProfilePreferences(preferences, context.dataStore.getPreferencesAfterPendingWrites())) {
                throw ProfileOperationSupersededException()
            }
            val prepared = iconPackBuildService.prepareBuild(
                expected.profileId, preferences, applicationList.toList(), lockManager.preservedRows().toList()
            )
            BuildPlan(prepared, undoTracker.step, savedProfileRevisions.getOrPut(expected.profileId) { 0L }, storageEpoch)
        }
        iconPackBuildService.build(plan.prepared, textMethod, progressMethod).copy(
            sessionToken = expected, undoStep = plan.undoStep,
            storageRevision = plan.storageRevision, storageEpoch = plan.storageEpoch
        )
    }

    suspend fun installIconPack(
        iconPack: BuiltIconPack,
        selection: InstallerSelection? = null
    ): ApkInstallOutcome {
        if (!canInstallSnapshot(iconPack)) {
            iconPackBuildService.releaseArtifacts(iconPack)
            return ApkInstallOutcome(ApkInstallResult.ABORTED)
        }
        val outcome = iconPackBuildService.install(iconPack, selection)
        finishInstallAttempt(iconPack, outcome)
        return outcome
    }

    /** Explicitly approved fallback after an update conflict: uninstall, then install the APK. */
    suspend fun replaceIconPack(
        iconPack: BuiltIconPack,
        selection: InstallerSelection? = null
    ): ApkInstallOutcome {
        if (!canInstallSnapshot(iconPack)) {
            iconPackBuildService.releaseArtifacts(iconPack)
            return ApkInstallOutcome(ApkInstallResult.ABORTED)
        }
        val outcome = iconPackBuildService.replace(iconPack, selection)
        finishInstallAttempt(iconPack, outcome)
        return outcome
    }

    private suspend fun canInstallSnapshot(iconPack: BuiltIconPack): Boolean = profileOperations.run {
        (iconPack.storageEpoch == null || iconPack.storageEpoch == storageEpoch) &&
            profileManager.profileExists(iconPack.profileId)
    }

    suspend fun releaseBuiltArtifacts(iconPack: BuiltIconPack, completed: Boolean = true) =
        iconPackBuildService.releaseArtifacts(iconPack, completed)

    suspend fun finishWithoutInstallation(iconPack: BuiltIconPack) {
        finishInstallAttempt(iconPack, ApkInstallOutcome(ApkInstallResult.ABORTED))
    }

    private suspend fun finishInstallAttempt(iconPack: BuiltIconPack, outcome: ApkInstallOutcome) {
        val success = outcome.result == ApkInstallResult.SUCCESS
        // Building the APK is the commitment, not installing it: someone who only wants their
        // icons stored can go through Build and dismiss the installer. Either way the save
        // matches the pack that was produced, so it counts as built.
        try {
            profileOperations.run {
                if (iconPack.storageEpoch != null && iconPack.storageEpoch != storageEpoch) return@run
                val ownsLiveUndo = iconPack.sessionToken?.let(::isCurrentSession) == true &&
                    undoTracker.step === iconPack.undoStep
                if (!profileManager.profileExists(iconPack.profileId)) return@run
                val storageUnchanged = iconPack.storageRevision == null ||
                    savedProfileRevisions[iconPack.profileId] == iconPack.storageRevision
                if (storageUnchanged) {
                    persistProfileIcons(
                        profileId = iconPack.profileId, apps = iconPack.profileApps,
                        preservedRows = iconPack.preservedRows, unbuiltAfter = false,
                        liveSession = iconPack.sessionToken
                    )
                    renkinPackStore.recordBuilt(iconPack.profileId)
                } else {
                    // A newer explicit save wins; the built baseline still describes the older APK.
                    renkinPackStore.recordBuilt(iconPack.profileId, iconPack.profileApps, iconPack.preservedRows)
                    profileManager.markUnbuilt(iconPack.profileId, true)
                }
                if (success) profileManager.recordBuiltPrimary(iconPack.profileId, iconPack.preferences)
                if (ownsLiveUndo) clearUndo()
                if (success) lockManager.clearProvenanceCache()
            }
        } finally {
            if (outcome.result != ApkInstallResult.CONFLICT) withContext(NonCancellable) {
                iconPackBuildService.releaseArtifacts(iconPack, completed = outcome.result != ApkInstallResult.TIMEOUT)
            }
        }
    }

    /**
     * Persists the active profile's icons without building (offered before switching away).
     * Locks the refresh output like a build does and marks the profile as saved-but-not-built.
     */
    suspend fun saveActiveProfileIcons(expected: ProfileSessionToken) = withContext(Dispatchers.Default) {
        runProfileOperation(expected) { persistActiveProfileIcons(unbuiltAfter = true) }
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
            unbuiltAfter = unbuiltAfter,
            liveSession = sessionToken
        )
    }

    /** Saves a completed operation back to the profile that started it, never the current one. */
    private suspend fun persistProfileIcons(
        profileId: Long,
        apps: List<PackageInfoStruct>,
        preservedRows: Collection<DbApplication>,
        unbuiltAfter: Boolean,
        liveSession: ProfileSessionToken?
    ) {
        // A profile can be deleted through another UI path while the system installer owns the
        // screen. Do not recreate icon rows that no longer have an owning profile.
        if (!profileManager.profileExists(profileId)) return
        renkinPackStore.save(profileId, apps, preservedRows)
        savedProfileRevisions[profileId] = (savedProfileRevisions[profileId] ?: 0L) + 1L
        profileManager.markUnbuilt(profileId, unbuiltAfter)

        // Only the matching live session may be mutated. An inactive profile will load these
        // persisted rows as locked when the user returns to it.
        if (liveSession == null || !isCurrentSession(liveSession)) return
        val savedByKey = apps.associateBy { it.key }
        val matchingKeys = applicationList.filter { savedByKey[it.key] == it }.mapTo(mutableSetOf()) { it.key }
        lockManager.releaseReplaced(apps.filter { it.key in matchingKeys && it.createdIcon != null }.mapTo(mutableSetOf()) { it.key })
        for (app in applicationList.toList()) {
            if (app.isRefreshMade && savedByKey[app.key] == app) editApplication(app, app.locked())
        }
        unsavedIconKeys = unsavedIconKeys - matchingKeys
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
            editApplication(app, updated, trackUnsaved = false)
        }
        lockManager.publish()
    }

    private suspend fun renderGlobal(
        base: IconPackDrawable,
        preferences: Preferences,
        apply: Boolean
    ): IconPackDrawable {
        val options = modifierFor(preferences, apply) ?: return base
        return try {
            iconGenService.applyModifier(base, options)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            base
        }
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
        val sourceOptions = GenerationOptions.fromPreferences(preferences, context)
        val options = sourceOptions.withSource {
            copy(primarySource = Source.ICON_PACK, primaryIconPack = sourcePack)
        }.withModifiers {
            withEffect { copy(imageEdit = ImageEdit.NONE) }
        }
        return try {
            iconGenService.getIconFromPackDrawable(app, sourcePack, drawableName, options)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    /** The drawable name [packPackage]'s appfilter maps to [app]'s component, if any. */
    private fun appFilterDrawableName(packPackage: String, app: PackageInfoStruct): String? =
        appManager.appFilterDrawableName(packPackage, app.toInstalledApplication())

    private suspend fun saveRenkinPack() {
        // A hand-picked or regenerated icon over a locked slot replaces the held-back original.
        val replacedKeys = applicationList.filter { it.createdIcon != null }.map { it.key }.toSet()
        lockManager.releaseReplaced(replacedKeys)
        renkinPackStore.save(activeProfileId, applicationList, lockManager.preservedRows())
        savedProfileRevisions[activeProfileId] = (savedProfileRevisions[activeProfileId] ?: 0L) + 1L
        unsavedIconKeys = emptySet()
    }

    suspend fun forceSync(expected: ProfileSessionToken) = withContext(Dispatchers.Default) {
        runProfileOperation(expected) {
            if (!iconPackRepo.iconPackLoaded) return@runProfileOperation
            iconPackRepo.load(installedApplicationRefs())
            lockManager.recordInstalledPacks(iconPacks)
            // Packs may have been updated/rebuilt — their provenance maps can be stale.
            lockManager.clearProvenanceCache()
            applicationList.forEach { renderTracker.changed(it.key) }
            // A freshly installed pack may be exactly the one some icons were waiting for.
            refreshLockedIcons()
        }
    }

    suspend fun refreshIconLocks(expected: ProfileSessionToken) = withContext(Dispatchers.Default) {
        runProfileOperation(expected) {
            applicationList.forEach { renderTracker.changed(it.key) }
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
            currentCoroutineContext().ensureActive()
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
                    .changeCalendar(entry.calendarEnabled, entry.calendarPrefix, entry.calendarPackName),
                trackUnsaved = false
            )
            lockManager.release(key)
        }
        lockManager.publish()
    }

    private fun editApplication(oldApp: PackageInfoStruct, newApp: PackageInfoStruct, trackUnsaved: Boolean = true) {
        val index = applicationList.indexOf(oldApp)
        if (index >= 0)
            editApplication(index, newApp, trackUnsaved)
    }

    private fun sameIconState(first: PackageInfoStruct, second: PackageInfoStruct): Boolean =
        first.createdIcon === second.createdIcon && first.baseIcon === second.baseIcon &&
            first.sourcePackName == second.sourcePackName && first.sourceUrl == second.sourceUrl &&
            first.calendarEnabled == second.calendarEnabled && first.calendarPrefix == second.calendarPrefix &&
            first.calendarPackName == second.calendarPackName && first.isCustom == second.isCustom &&
            first.isLegacy == second.isLegacy && first.isFallback == second.isFallback

    private fun editApplication(index: Int, newApp: PackageInfoStruct, trackUnsaved: Boolean = true) {
        renderTracker.changed(newApp.key)
        if (!sameIconState(_applicationList[index], newApp)) {
            undoTracker.discard(newApp.key)
            if (trackUnsaved) unsavedIconKeys = unsavedIconKeys + newApp.key
        }
        _applicationList[index] = newApp
    }

    suspend fun getIconPackIcons(iconPackName: String, options: GenerationOptions, drawables: List<ResourceDrawable>): Map<ResourceDrawable, IconPackDrawable?> =
        iconGenService.getIconPackIcons(iconPackName, options, drawables)

    internal fun packBrowserPreviews(): PackBrowserPreviews =
        PackBrowserPreviews(appManager, ::getIconPackIcons)

    suspend fun getIconPackDropdownIcons(application: InstalledApplication?): Map<String, ResourceDrawable> =
        iconPackRepo.getDropdownIcons(application)

    // Only the last change is undoable; a second one replaces it, exactly like the snackbar it
    // is offered through.
    private val undoTracker = IconUndoTracker()

    /** How many icons the pending undo would put back; 0 when there is nothing to offer. */
    val undoableIconCount: Int get() = undoTracker.size
    val currentUndoStep: IconUndoStep? get() = undoTracker.step

    /** Captures the overwritten rows after their replacement has committed. */
    private fun captureUndoRows(rows: List<PackageInfoStruct>, persisted: Boolean, unsavedBefore: Set<String>) {
        undoTracker.capture(rows, activeProfileId, persisted, unsavedBefore)
    }

    /** Drops the offer — after a build, a profile switch or anything that invalidates the rows. */
    private fun clearUndo() = undoTracker.clear()

    /**
     * Puts the captured rows back. Returns false when the step no longer applies: another
     * profile is active, or the apps it described are gone.
     */
    suspend fun undoLastIconChange(expected: ProfileSessionToken, step: IconUndoStep): Boolean = withContext(Dispatchers.Default) {
        runProfileOperation(expected) {
            if (!ownsUndo(step)) return@runProfileOperation false
            val pending = undoTracker.step ?: return@runProfileOperation false
            val persisted = pending.persisted
            val restoration = undoTracker.restorationFor(applicationList, activeProfileId)
            if (restoration.isEmpty()) return@runProfileOperation false
            for ((index, row) in restoration) editApplication(index, row)
            val restoredKeys = restoration.mapTo(mutableSetOf()) { (_, row) -> row.key }
            unsavedIconKeys = (unsavedIconKeys - restoredKeys) + pending.unsavedBefore.intersect(restoredKeys)
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
    private fun clearRefreshedIcons() {
        applicationList.forEach { renderTracker.changed(it.key) }
        clearUndo()
        for (app in applicationList.toList()) {
            if (app.isRefreshMade) editApplication(app, app.changeExport(null))
        }
    }

    suspend fun clearIcons(expected: ProfileSessionToken): ProfileSessionToken = withContext(Dispatchers.Default) {
        runProfileOperation(expected) {
            invalidateSession()
            // Explicit removal includes held-back rows and calendar-only assignments.
            lockManager.clear()
            resetInMemoryIcons()
            saveRenkinPack()
            sessionToken
        }
    }

    suspend fun updateProfilePreferences(
        expected: ProfileSessionToken,
        update: suspend androidx.datastore.core.DataStore<Preferences>.() -> Unit
    ) {
        requireCurrentSession(expected)
        requireRecipeAvailable(expected)
        profileOperations.runWithBoundedWait {
            requireCurrentSession(expected)
            requireRecipeAvailable(expected)
            currentCoroutineContext().ensureActive()
            context.dataStore.update()
        }
    }

    suspend fun selectPrimarySource(
        expected: ProfileSessionToken,
        source: Source,
        packageName: String?
    ): ProfileSessionToken = runProfileOperation(expected) {
        requireRecipeAvailable(expected)
        context.dataStore.setPrimarySource(source, packageName)
        if (source == Source.NONE) clearRefreshedIcons()
        sessionToken
    }

    /** Blanks every in-memory icon and calendar opt-in (profile switch, restore, clear). */
    private fun resetInMemoryIcons() {
        for (app in applicationList.toList()) {
            editApplication(app, app.changeExport(null).changeCalendar(false, null, null), trackUnsaved = false)
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
        profileOperations.run { profileManager.createProfile(name, description, packLabel) }

    suspend fun updateProfileDetails(id: Long, name: String, description: String, packLabel: String) =
        profileOperations.run { profileManager.updateProfileDetails(id, name, description, packLabel) }

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
    suspend fun setHideMissingPackWarning(expected: ProfileSessionToken, hide: Boolean) =
        runProfileOperation(expected) { profileManager.setHideMissingPackWarning(hide) }

    /**
     * Switches the active profile: snapshots the leaving profile's generation preferences,
     * restores the target's, and swaps the in-memory icons for the target's saved set. For the
     * leaving profile this behaves like an app restart — unbuilt edits are not persisted.
     */
    suspend fun switchProfile(
        newProfileId: Long, saveFirst: Boolean = false, saveIfChanged: Boolean = false
    ): ProfileSessionToken = withContext(Dispatchers.Default) {
        ensureInitialized()
        profileOperations.run {
            if (newProfileId == activeProfileId || !profileManager.profileExists(newProfileId)) {
                return@run sessionToken
            }
            if (saveFirst || (saveIfChanged && unsavedIconKeys.isNotEmpty())) {
                persistActiveProfileIcons(unbuiltAfter = true)
            }
            isProfileSwitching = true
            try {
                switchProfileLocked(newProfileId)
            } finally {
                isProfileSwitching = false
            }
            sessionToken
        }
    }

    /** Runs only while [profileOperations] is held. */
    private suspend fun switchProfileLocked(newProfileId: Long) {
        if (newProfileId == activeProfileId || !profileManager.profileExists(newProfileId)) return
        invalidateSession()
        if (!profileManager.switchTo(newProfileId)) return
        // The captured rows belong to the profile being left; they must not be restorable here.
        clearUndo()
        // Reset the in-memory icons and load exactly the target selected above.
        resetInMemoryIcons()
        loadRenkinPack(newProfileId)
        unsavedIconKeys = emptySet()
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
                invalidateSession()
                reloadActiveProfileLocked()
            } finally {
                isProfileSwitching = false
            }
        }
    }

    suspend fun <T> replaceProfileSession(replaceStorage: suspend () -> T): T =
        withContext(Dispatchers.Default) {
            profileOperations.run {
                currentCoroutineContext().ensureActive()
                isProfileSwitching = true
                invalidateSession()
                storageEpoch++
                try {
                    val result = replaceStorage()
                    reloadActiveProfileLocked()
                    result
                } catch (error: Exception) {
                    // A failed import may already have replaced part of storage. Keep the live
                    // session aligned with it; backup rollback belongs to the restore protocol.
                    withContext(NonCancellable) {
                        runCatching { reloadActiveProfileLocked() }.onFailure(error::addSuppressed)
                    }
                    throw error
                } finally {
                    isProfileSwitching = false
                }
            }
        }

    private suspend fun reloadActiveProfileLocked() {
        profileManager.reloadActiveId()
        resetInMemoryIcons()
        loadRenkinPack(activeProfileId)
        unsavedIconKeys = emptySet()
    }

    // ---- Locks & verdicts (delegated to IconLockManager) -----------------------------

    /**
     * Looks up any referenced pack still lacking a paid/free verdict (quiet best effort).
     * Returns true when a verdict became decisive — callers reload so freshly-verified-free
     * icons unlock without a restart.
     */
    suspend fun verifyPendingVerdicts(): Boolean = lockManager.verifyPendingVerdicts()

    data class MissingPackStatus(
        val packs: List<IconLockManager.MissingPack>,
        val hideWarning: Boolean
    )

    suspend fun missingPackStatus(expected: ProfileSessionToken): MissingPackStatus =
        runProfileOperation(expected) {
            MissingPackStatus(
                lockManager.missingPackSummary(),
                profileManager.activeProfile()?.hideMissingPackWarning == true
            )
        }

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
            val sourceOptions = GenerationOptions.fromPreferences(preferences, context)
            val requestedFallbackSource = fallbackSource
            val options = sourceOptions.withSource { copy(fallbackSource = requestedFallbackSource) }
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

}
