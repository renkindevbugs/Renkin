package dev.renkinProject.renkin

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.renkinProject.renkin.apk.ApkUninstaller
import dev.renkinProject.renkin.apk.ApkInstallResult
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.BuiltIconPack
import dev.renkinProject.renkin.apk.PackChange
import dev.renkinProject.renkin.apk.packChanges
import dev.renkinProject.renkin.apk.unsavedApplicationKeys
import dev.renkinProject.renkin.apk.IconGenerationService
import dev.renkinProject.renkin.apk.IconLockManager
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.AppFilterNoIconKey
import dev.renkinProject.renkin.data.AppSortOrder
import dev.renkinProject.renkin.data.AppSortOrderKey
import dev.renkinProject.renkin.data.DarkMode
import dev.renkinProject.renkin.data.DarkModeKey
import dev.renkinProject.renkin.data.HideProfileShareWarningKey
import dev.renkinProject.renkin.data.OnboardingSeenKey
import dev.renkinProject.renkin.data.PrimaryIconPackKey
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.data.setBooleanValue
import dev.renkinProject.renkin.data.setEnumValue
import dev.renkinProject.renkin.data.setPrimarySource
import dev.renkinProject.renkin.data.transfer.BackupManager
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconSortOrder
import dev.renkinProject.renkin.icon.creator.PackBrowserPreviews
import dev.renkinProject.renkin.icon.creator.PackIconPreview
import dev.renkinProject.renkin.icon.creator.PackRowPreviews
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal fun isProfileSummaryReady(
    startupComplete: Boolean,
    isProfileSwitching: Boolean,
    baselineProfileId: Long?,
    activeProfileId: Long
): Boolean = startupComplete &&
    !isProfileSwitching &&
    baselineProfileId == activeProfileId

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

/**
 * Outcome of a hand-picked icon assignment. Callers that act on the result afterwards (the
 * icon-watch modal deletes the rule) must be able to tell "applied" from "nothing happened".
 */
enum class IconApplyResult {
    APPLIED,
    /** The pick's true origin is a pack this device doesn't own — withheld. */
    LOCKED,
    /** The target row was gone by the time the icon finished rendering. */
    TARGET_GONE,
    /** Another watch deep-link switched profiles while this suggestion was being applied. */
    PROFILE_CHANGED,
    /** Rendering or source resolution failed; the watch rule must stay available to retry. */
    FAILED
}

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val appProvider: ApplicationProvider,
    private val watchRepo: WatchRepository,
    private val backupManager: BackupManager
) : AndroidViewModel(application), IconPreviewBuilder {
    /**
     * Keeps profile-session bookkeeping on the same side of a profile switch as the provider
     * operation that changed the icon. The provider separately protects its live icon list.
     */
    private val profileSessionOperations = Mutex()

    // ---- Model state exposed to the UI (read-only) -------------------------------
    // The UI observes these instead of reaching through to ApplicationProvider, so the
    // view model stays the single point of contact with the model layer. Each is backed
    // by Compose state in the provider/repository, so reads in composition stay reactive.

    /** The loaded apps, each with its current (created) icon. Edited via [applyIcon]. */
    val applicationList: List<PackageInfoStruct> get() = appProvider.applicationList

    /** Saved colours/gradients offered by every colour sheet. */
    val colorPresets: kotlinx.coroutines.flow.StateFlow<List<dev.renkinProject.renkin.data.ColorPreset>> =
        appProvider.colorPresets().stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            emptyList()
        )

    fun saveColorPreset(name: String, style: String) {
        viewModelScope.launch {
            appProvider.saveColorPreset(name, style)
            _toastEvents.trySend(R.string.savedColorsAdded)
        }
    }

    fun deleteColorPreset(id: Long) {
        viewModelScope.launch {
            appProvider.deleteColorPreset(id)
            _toastEvents.trySend(R.string.savedColorsRemoved)
        }
    }

    /** The installed icon packs available as icon sources. */
    val iconPacks: List<IconPack> get() = appProvider.iconPacks

    /** True once the icon packs have finished loading. */
    val iconPackLoaded: Boolean get() = appProvider.iconPackLoaded

    /** True once the app list has finished loading. */
    val applicationsLoaded: Boolean get() = appProvider.applicationsLoaded

    /** True once apps, icon packs AND the saved profile icons have all loaded (cold start). */
    val startupComplete: Boolean get() = appProvider.startupComplete

    var startupLoading by mutableStateOf(false)
        private set

    var startupFailed by mutableStateOf(false)
        private set

    // Keys ("package/activity") of the apps already in the last built/saved pack.
    // An app with an icon whose key is NOT here is "added" (pending build); a key here
    // whose app no longer has an icon is "removed". Reloaded after each successful build,
    // so the change state is a diff against what was actually built (survives refresh).
    /**
     * Fingerprints of what the last build shipped and of what is stored right now. Together with
     * the session's [updatedKeys] they answer "what would a build change?" — and unlike the key
     * sets alone they survive saving and restarting.
     */
    var builtIconHashes by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var savedIconHashes by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /**
     * Profile whose build/saved baselines are currently represented by the fields above.
     * During a switch the provider swaps [applicationList] before these database reads finish;
     * keeping the owner explicit prevents the UI from comparing the new list with the old profile.
     */
    private var changeBaselinesProfileId by mutableStateOf<Long?>(null)

    /** True only when the hero progress and build diff describe the active profile. */
    val profileSummaryReady: Boolean
        get() = isProfileSummaryReady(
            startupComplete = startupComplete,
            isProfileSwitching = isProfileSwitching,
            baselineProfileId = changeBaselinesProfileId,
            activeProfileId = activeProfileId
        )

    private fun invalidateChangeBaselines() {
        changeBaselinesProfileId = null
    }

    /**
     * Re-reads the baselines for [profileId] after a build. Called however the install ended:
     * producing the APK is what counts as built, so a dismissed installer must not leave the
     * change list claiming the icons are still pending.
     */
    private suspend fun syncBuildBaselines(profileId: Long) {
        if (appProvider.activeProfileId != profileId) return
        loadChangeBaselines(profileId)
    }

    var builtKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    // Keys whose icons were manually changed this session (via applyIcon with a non-null icon).
    // Distinct from builtKeys: an app in both sets had an icon already and was re-edited.
    // Reset after every successful build.
    var updatedKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * What a build would add, change or remove. Derived here rather than in each screen so the
     * hero card's badge and the changes sheet read one computation instead of running the same
     * diff over the whole app list twice.
     *
     * derivedStateOf, not a plain getter: [applicationList] is one long-lived snapshot list whose
     * identity never changes, so only tracking its contents keeps the result from going stale.
     */
    val pendingPackChanges: List<PackChange> by derivedStateOf {
        if (profileSummaryReady) {
            packChanges(applicationList, builtIconHashes, savedIconHashes, updatedKeys)
        } else {
            emptyList()
        }
    }

    // Set when opened from an icon-watch notification; the home screen shows the apply
    // modal for this suggestion.
    var pendingWatchSuggestionId by mutableStateOf<Long?>(null)
        private set

    fun setPendingWatchSuggestion(id: Long) { pendingWatchSuggestionId = id }

    // True when a watch notification pointed at a profile that has since been deleted — the
    // home screen explains why the suggestion can't be applied instead of silently ignoring it.
    var watchProfileMissing by mutableStateOf(false)
        private set

    fun clearWatchProfileMissing() { watchProfileMissing = false }

    /**
     * Opens a watch suggestion from its notification: switches to the profile that owns it
     * first (auto-saving the active profile's unsaved work — the user opted into that), then
     * shows the apply modal. Waits out a cold start so the switch doesn't race initialization.
     */
    fun openSuggestionInProfile(suggestionId: Long) {
        viewModelScope.launch {
            // Cold start from a notification: the app list / saved icons may still be loading.
            // Safety beats a timeout here: opening in the current profile after a slow startup
            // could apply the icon to the wrong profile. The activity-scoped coroutine is
            // cancelled naturally if startup cannot finish and the activity goes away.
            while (!appProvider.startupComplete) delay(50)
            // The database is authoritative. Intent extras can be stale (or supplied by another
            // app because MainActivity is exported), so never trust them for profile ownership.
            val suggestion = watchRepo.getSuggestion(suggestionId) ?: return@launch
            val profileId = watchRepo.getRule(suggestion.ruleId)?.rule?.profileId ?: return@launch
            if (!appProvider.profileExists(profileId)) {
                watchProfileMissing = true
                return@launch
            }
            if (profileId != appProvider.activeProfileId) {
                performSwitch(profileId, saveFirst = false, saveIfChanged = true)
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

    /**
     * A change that can still be taken back: [messageRes] describes it, [count] fills its
     * plural. Shown as a snackbar with an Undo action rather than a toast, because a toast
     * cannot be acted on.
     */
    data class UndoPrompt(@androidx.annotation.StringRes val messageRes: Int, val count: Int)

    // CONFLATED, unlike the toast channel: only one change is undoable at a time, so a queue of
    // older prompts would offer to undo something the tracker has already forgotten.
    private val _undoEvents = Channel<UndoPrompt>(Channel.CONFLATED)
    val undoEvents = _undoEvents.receiveAsFlow()

    /** Puts the last icon change back. Silent when the change no longer applies. */
    fun undoLastIconChange() {
        viewModelScope.launch {
            if (appProvider.undoLastIconChange()) {
                _toastEvents.trySend(R.string.undoRestored)
            } else {
                _toastEvents.trySend(R.string.undoUnavailable)
            }
        }
    }

    private fun loadStartup() {
        if (startupLoading || profileSummaryReady) return
        startupLoading = true
        startupFailed = false
        viewModelScope.launch {
            try {
                appProvider.ensureInitialized()
                resetChangeBaselines()
                refreshMissingPacks()
                // Classify any source packs that still lack a paid/free verdict (quiet best
                // effort; imported-offline icons stay locked until a lookup succeeds). A verdict
                // becoming decisive can unlock icons — reload so that shows without a restart.
                if (runCatching { appProvider.verifyPendingVerdicts() }.getOrDefault(false)) {
                    invalidateChangeBaselines()
                    appProvider.reloadActiveProfile()
                    resetChangeBaselines()
                    refreshMissingPacks(prompt = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                startupFailed = true
                Log.error("MainViewModel", "Startup loading failed", e)
            } finally {
                startupLoading = false
            }
        }
    }

    fun retryStartup() = loadStartup()

    init {
        // Loaded once. The Renkin pack reads the app list, so it runs after the
        // apps are loaded; icon packs are independent and load in parallel. The heavy
        // work hops to Dispatchers.Default inside each call, so viewModelScope (main)
        // is fine here.
        loadStartup()
    }

    // ---- Operation orchestration -------------------------------------------------
    // The heavy work lives in suspend functions on ApplicationProvider (each hops to
    // Dispatchers.Default internally), so these run safely on viewModelScope (main).
    // UI state is exposed as Compose state; composables read it and call these instead
    // of spinning up their own lifecycleScope coroutines.

    /** True while [refresh] is regenerating icons. Drives the refresh spinner and blocks build. */
    var isRefreshing by mutableStateOf(false)
        private set

    /** Regenerates every app from a snapshot taken after pending DataStore writes complete. */
    fun refresh() {
        if (isRefreshing) return
        // Set synchronously so a second tap cannot enqueue another refresh before the coroutine
        // starts, and so the top-bar action immediately communicates that work is running.
        isRefreshing = true
        viewModelScope.launch {
            try {
                val profileId = appProvider.activeProfileId
                val preferences = getApplication<Application>().dataStore
                    .getPreferencesAfterPendingWrites()
                val iconPackageName = preferences.getStringValue(PrimaryIconPackKey)
                if (!appProvider.iconPackLoaded && iconPackageName.isNotEmpty()) {
                    _toastEvents.trySend(R.string.syncText)
                    return@launch
                }
                // Icons whose real origin (via an own pack used as source) isn't owned on
                // this device are withheld — say so instead of leaving silent empty slots.
                val withheld = appProvider.refreshIcons(profileId)
                if (withheld == null) {
                    _toastEvents.trySend(R.string.profileStillLoading)
                } else {
                    if (withheld > 0) _toastEvents.trySend(R.string.refreshLockedSkipped)
                    // Only worth offering when the refresh actually overwrote something: with
                    // "Refresh replaces existing icons" off it only fills empty slots.
                    appProvider.undoableIconCount.takeIf { it > 0 }?.let { replaced ->
                        _undoEvents.trySend(UndoPrompt(R.string.undoIconsRefreshed, replaced))
                    }
                }
            } finally {
                // Without this a failed refresh would leave the spinner on forever and block builds.
                isRefreshing = false
            }
        }
    }

    /** Persists the hero source, then immediately applies the same clear/refresh semantics. */
    fun selectPrimarySource(source: Source, packageName: String?) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.setPrimarySource(source, packageName)
            if (source == Source.NONE) appProvider.clearRefreshedIcons() else refresh()
        }
    }

    fun setAppSortOrder(order: AppSortOrder) = updatePreferences {
        setEnumValue(AppSortOrderKey, order)
    }

    fun setMissingIconFilter(enabled: Boolean) = updatePreferences {
        setBooleanValue(AppFilterNoIconKey, enabled)
    }

    fun setOnboardingSeen(seen: Boolean) = updatePreferences {
        setBooleanValue(OnboardingSeenKey, seen)
    }

    fun setDarkMode(mode: DarkMode) = updatePreferences {
        setEnumValue(DarkModeKey, mode)
    }

    fun hideProfileShareWarning() = updatePreferences {
        setBooleanValue(HideProfileShareWarningKey, true)
    }

    private fun updatePreferences(
        block: suspend DataStore<Preferences>.() -> Unit
    ) {
        viewModelScope.launch { getApplication<Application>().dataStore.block() }
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
            packageNames.distinct().associateWith { pkg ->
                runCatching { pm.getPackageInfo(pkg, 0).firstInstallTime }.getOrDefault(0L)
            }
        }

    /** True if the generated pack at [packageName] is currently installed on the device. */
    private suspend fun isIconPackInstalled(packageName: String): Boolean = runCatching {
        getApplication<Application>().packageManager.getPackageInfo(packageName, 0)
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

    private data class PendingInstallFallback(
        val pack: BuiltIconPack,
        val wasUpdate: Boolean,
        val packLabel: String
    )

    private var pendingInstallFallback: PendingInstallFallback? = null
    var installFallbackPending by mutableStateOf(false)
        private set

    fun dismissInstallFallback() {
        pendingInstallFallback = null
        installFallbackPending = false
    }

    /** Runs only after the user accepts that the conflicting installed pack must be replaced. */
    fun confirmInstallFallback() {
        val pending = pendingInstallFallback ?: return
        dismissInstallFallback()
        viewModelScope.launch {
            try {
                buildStep = getApplication<Application>().getString(R.string.buildReplacing)
                when (appProvider.replaceIconPack(pending.pack)) {
                    ApkInstallResult.SUCCESS -> completeSuccessfulInstall(pending)
                    else -> _toastEvents.trySend(R.string.iconPackInstallFailed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.error("MainViewModel", "Icon pack replacement failed", e)
                _toastEvents.trySend(R.string.iconPackInstallFailed)
            } finally {
                buildStep = null
                buildProgress = null
            }
        }
    }

    /** Builds from a preference snapshot taken after pending UI writes have completed. */
    fun build(profileId: Long) {
        if (buildStep != null) return
        if (appProvider.isProfileSwitching || profileId != appProvider.activeProfileId) {
            _toastEvents.trySend(R.string.profileStillLoading)
            return
        }
        // Claim the operation synchronously so the preview result cannot enqueue it twice.
        buildStep = ""
        viewModelScope.launch {
            try {
                val preferences = getApplication<Application>().dataStore
                    .getPreferencesAfterPendingWrites()
                val pack = appProvider.buildAndSignIconPack(
                    profileId,
                    preferences,
                    // A new step ends the per-app phase, so the bar goes back to indeterminate.
                    textMethod = { buildStep = it; buildProgress = null },
                    progressMethod = { done, total -> buildProgress = done to total }
                )
                // The system install is the slow part (2-3s) — show it as its own step so the
                // dialog reflects what's happening. "Updating" when our pack is already
                // installed, "Installing" for a first build. Decided before installing, so it
                // also tells us which follow-up instructions dialog to show afterwards.
                val wasUpdate = isIconPackInstalled(pack.packageName)
                val pending = PendingInstallFallback(pack, wasUpdate, pack.packLabel)
                buildStep = getApplication<Application>().getString(
                    if (wasUpdate) R.string.buildUpdating else R.string.buildInstalling
                )
                when (appProvider.installIconPack(pack)) {
                    ApkInstallResult.SUCCESS -> completeSuccessfulInstall(pending)
                    ApkInstallResult.CONFLICT -> {
                        pendingInstallFallback = pending
                        installFallbackPending = true
                    }
                    ApkInstallResult.ABORTED,
                    ApkInstallResult.FAILED -> {
                        // The pack was built and saved; only its installation didn't happen.
                        syncBuildBaselines(pack.profileId)
                        _toastEvents.trySend(R.string.iconPackInstallFailed)
                    }
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

    private suspend fun completeSuccessfulInstall(pending: PendingInstallFallback) {
        // The pack now ships what is on screen; undoing a refresh from before the build would
        // silently disagree with the installed icons.
        appProvider.clearUndo()
        // The next-steps dialog replaces the old "installed!" toast: what to do in
        // the launcher differs between a first install and an update.
        buildOutcome = BuildOutcomeInfo(
            if (pending.wasUpdate) BuildOutcome.UPDATE else BuildOutcome.FIRST_INSTALL,
            pending.packLabel
        )
        // A watch deep link can switch profiles while the system installer is in front. Never
        // replace that profile's UI baseline with the pack that finished in the background.
        syncBuildBaselines(pending.pack.profileId)
        // The save dropped locked originals the user replaced by hand — the
        // missing-pack warning must not keep counting them after a build.
        refreshMissingPacks(prompt = false)
    }

    // Records that [app] was hand-edited this session (so the build preview marks it "changed").
    // Clearing an icon (icon == null) is a removal, not a change, so it isn't recorded.
    private fun markUpdated(app: PackageInfoStruct, icon: IconPackDrawable?) {
        if (icon != null) updatedKeys = updatedKeys + app.key
    }

    /**
     * Regenerates one app's icon with the current recipe — the quick action for "this one came
     * out wrong" without refreshing the whole list.
     */
    fun refreshSingleIcon(app: PackageInfoStruct) {
        viewModelScope.launch {
            // Undo is offered only when an existing icon was actually replaced — the provider
            // knows that, the caller doesn't (a locked origin is withheld and changes nothing).
            if (appProvider.refreshIcon(activeProfileId, app)) {
                _undoEvents.trySend(UndoPrompt(R.string.undoIconRefreshed, 1))
            }
        }
    }

    /**
     * Assigns (or clears, when [icon] is null) the created icon for [app].
     * [sourcePackName] is the pack the icon was taken from (null/empty when not from a pack).
     * An icon picked from one of our own packs is attributed to its recorded origin — and
     * withheld (with a toast) when that origin is a pack this device doesn't own.
     */
    fun applyIcon(app: PackageInfoStruct, icon: IconPackDrawable?, sourcePackName: String? = null) =
        applyPickedIcon(app, icon, sourcePackName) { live, origin, rendered ->
            // Hand-picked icons are locked immediately: a refresh never replaces them.
            live.changeExport(rendered, sourcePackName = origin, isRefreshMade = false, isCustom = true, isLegacy = false, baseIcon = icon)
        }

    /**
     * Same assignment as [applyIcon], but awaited and reporting its outcome. The icon-watch
     * apply modal deletes the rule (which cascades the suggestion) once the icon is applied —
     * fire-and-forget would drop the rule even when the target row was gone and nothing landed.
     */
    suspend fun applyWatchIcon(
        expectedProfileId: Long,
        app: PackageInfoStruct,
        icon: IconPackDrawable,
        sourcePackName: String?
    ): IconApplyResult {
        return try {
            profileSessionOperations.withLock {
                when (
                    appProvider.applyWatchIcon(
                        expectedProfileId,
                        app,
                        icon,
                        sourcePackName
                    )
                ) {
                    ApplicationProvider.WatchIconApplyResult.APPLIED -> {
                        markUpdated(app, icon)
                        IconApplyResult.APPLIED
                    }
                    ApplicationProvider.WatchIconApplyResult.LOCKED -> IconApplyResult.LOCKED
                    ApplicationProvider.WatchIconApplyResult.TARGET_GONE -> IconApplyResult.TARGET_GONE
                    ApplicationProvider.WatchIconApplyResult.PROFILE_CHANGED -> IconApplyResult.PROFILE_CHANGED
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("MainViewModel", "Could not apply watched icon", error)
            IconApplyResult.FAILED
        }
    }

    /**
     * Restores one app to its launcher default. Unlike assigning a null icon generically, this
     * also discards an imported row held behind a missing pack and clears calendar rotation.
     */
    fun resetIcon(app: PackageInfoStruct) {
        viewModelScope.launch {
            if (!appProvider.resetIcon(activeProfileId, app)) return@launch
            refreshMissingPacks(prompt = false)
            _undoEvents.trySend(UndoPrompt(R.string.undoIconReset, 1))
        }
    }

    /**
     * Applies the icon and the calendar selection together. The edit dialog tracks the
     * calendar prefix/source-pack in local state as the user browses; persisting only the
     * icon (via [applyIcon]) would leave the stored `<calendar>` prefix pointing at the
     * previously chosen pack, so launchers that prefer `<calendar>` would keep showing the
     * old icon. Setting both in one edit keeps the static `<item>` and `<calendar>` in sync.
     */
    fun applyIcon(
        app: PackageInfoStruct,
        icon: IconPackDrawable?,
        calendarEnabled: Boolean,
        calendarPrefix: String?,
        calendarPackName: String?,
        sourcePackName: String?,
        // Attribution reference when the icon came from an online FOSS library (vector tab).
        sourceUrl: String? = null
    ) = applyPickedIcon(app, icon, sourcePackName) { live, origin, rendered ->
        live.changeExport(rendered, sourcePackName = origin, isRefreshMade = false, isCustom = true, isLegacy = false, baseIcon = icon, sourceUrl = sourceUrl)
            .changeCalendar(calendarEnabled, calendarPrefix, calendarPackName)
    }

    /**
     * The shared hand-pick gate: resolves the picked pack to its true origin, blocks the
     * pick (with a toast) when that origin is locked on this device, and applies [edit] to
     * the app's LIVE row. The row is looked up by key after the suspension — the list can
     * mutate (refresh, sync) while resolvePickedSource runs, so an index captured by the
     * dialog could go stale.
     */
    private fun applyPickedIcon(
        app: PackageInfoStruct,
        icon: IconPackDrawable?,
        sourcePackName: String?,
        edit: (live: PackageInfoStruct, origin: String?, rendered: IconPackDrawable?) -> PackageInfoStruct
    ) {
        viewModelScope.launch {
            val (origin, locked) = appProvider.resolvePickedSource(app, if (icon == null) null else sourcePackName)
            if (icon != null && locked) {
                _toastEvents.trySend(R.string.iconOriginLocked)
                return@launch
            }
            val rendered = icon?.let {
                val preferences = getApplication<Application>().dataStore
                    .getPreferencesAfterPendingWrites()
                appProvider.renderCustomIcon(it, preferences)
            }
            val index = appProvider.applicationList.indexOfFirst { it.key == app.key }
            if (index < 0) return@launch
            appProvider.editApplication(index, edit(appProvider.applicationList[index], origin, rendered))
            markUpdated(app, icon)
        }
    }

    // ---- Global icon modifiers -----------------------------------------------------

    /**
     * Called when the Global options activity returns: the work happened on the shared
     * provider (via GlobalOptionsViewModel), so this only refreshes the session bookkeeping —
     * hand-edited keys count as this session's edits, and a Save (which persisted the
     * profile) moves the change baselines exactly like a save-before-switch does.
     */
    fun onGlobalOptionsClosed(editedKeys: Set<String>, applied: Boolean) {
        updatedKeys = updatedKeys + editedKeys
        if (applied) {
            viewModelScope.launch {
                resetChangeBaselines()
                refreshMissingPacks(prompt = false)
            }
        }
    }

    /** Live count of icons taken from each pack — orders the per-app icon picker. */
    fun packUsageCounts(): Map<String, Int> = appProvider.packUsageCounts()

    /** How many installed apps each pack has an icon for — the pack picker's coverage line. */
    fun packMatchedAppCounts(): Map<String, Int> = appProvider.packMatchedAppCounts()

    /** Per-pack usage by TRUE origin (locked icons included) for the stats dialog. */
    suspend fun packUsageEntries(): List<ApplicationProvider.PackUsage> = appProvider.packUsageEntries()

    /**
     * Uninstalls the app's own generated icon pack. Emits a toast event for the outcome
     * (uninstalled / not installed); the system shows its own uninstall confirmation.
     */
    fun deleteIconPack() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val packageName = appProvider.activePackPackageName()
            if (!isIconPackInstalled(packageName)) {
                _toastEvents.trySend(R.string.iconPackNotInstalled)
                return@launch
            }
            if (ApkUninstaller(context).uninstall(packageName)) {
                _toastEvents.trySend(R.string.iconPackUninstalled)
            }
        }
    }

    /** Re-reads the installed icon packs (and unlocks icons whose pack just arrived). */
    /** True while [sync] runs — the Settings row shows it and blocks re-entry. */
    var syncing by mutableStateOf(false)
        private set

    fun sync() {
        if (syncing) return
        syncing = true
        viewModelScope.launch {
            try {
                appProvider.forceSync()
                packBrowserPreviews.clear()
                // The sync may have unlocked held-back icons — the badge/banner must follow.
                refreshMissingPacks(prompt = false)
                _toastEvents.trySend(R.string.packsSynced)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Re-parsing every installed pack can fail on a malformed one — say so instead
                // of leaving the row spinning with no outcome.
                Log.error("MainViewModel", "Icon pack sync failed", e)
                _toastEvents.trySend(R.string.packsSyncFailed)
            } finally {
                syncing = false
            }
        }
    }

    // True while the app list reload runs — drives the home list's pull-to-refresh spinner.
    var appsRefreshing by mutableStateOf(false)
        private set

    /** Reloads apps, icon packs and saved rows while preserving this session's unsaved keys. */
    fun refreshApps() {
        if (appsRefreshing) return
        viewModelScope.launch {
            appsRefreshing = true
            try {
                appProvider.reloadPreservingSession(unsavedKeys())
                packBrowserPreviews.clear()
                // An uninstalled app no longer has a visible row to carry a session edit.
                val liveKeys = appProvider.applicationList.mapTo(mutableSetOf()) { it.key }
                updatedKeys = updatedKeys.filterTo(mutableSetOf()) { it in liveKeys }
                _toastEvents.trySend(R.string.appListRefreshed)
            } finally {
                appsRefreshing = false
            }
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
        expectedHash: String,
        options: GenerationOptions
    ): IconGenerationService.ValidatedPackIcon =
        appProvider.getValidatedIconFromPackDrawable(app, packPackage, drawableName, expectedHash, options)

    /** The selectable drawables for the icon-pack dropdown, keyed by display name. */
    suspend fun iconPackDropdownIcons(application: InstalledApplication?): Map<String, ResourceDrawable> =
        appProvider.getIconPackDropdownIcons(application)

    // ---- Icon-pack browser previews ---------------------------------------------------
    // The pack browser's heavy work (enumerating, filtering and rasterising a pack's drawables,
    // plus the row-preview cache) lives in PackBrowserPreviews; the view model just forwards to it
    // so the UI still only talks to the view model. buildPackIcons is wired to the provider.
    private val packBrowserPreviews by lazy {
        appProvider.packBrowserPreviews()
    }

    /** Collapsed row previews for the icon-pack browser (see [PackBrowserPreviews.rowPreviews]). */
    suspend fun packRowPreviews(
        iconPack: IconPack,
        sortOrder: IconSortOrder,
        query: String,
        options: GenerationOptions,
        component: InstalledApplication? = null
    ): PackRowPreviews = packBrowserPreviews.rowPreviews(iconPack, sortOrder, query, options, component)

    /** Streaming full-pack grid previews for the browser (see [PackBrowserPreviews.detailPreviews]). */
    suspend fun packDetailPreviews(
        iconPack: IconPack,
        sortOrder: IconSortOrder,
        query: String,
        options: GenerationOptions,
        component: InstalledApplication? = null,
        onChunk: (List<PackIconPreview>) -> Unit
    ) = packBrowserPreviews.detailPreviews(iconPack, sortOrder, query, options, component, onChunk)

    // ---- Profiles -----------------------------------------------------------------

    /** All profiles for the switcher (default first). */
    val profiles = appProvider.profilesFlow()

    val activeProfileId: Long get() = appProvider.activeProfileId

    val isProfileSwitching: Boolean get() = appProvider.isProfileSwitching

    /**
     * True when the active profile has changes its saved set doesn't: unsaved refresh output,
     * hand edits from this session, or removals against the saved pack. Drives the
     * save-before-switch prompt.
     */
    fun hasUnsavedChanges(): Boolean {
        return unsavedKeys().isNotEmpty()
    }

    private fun unsavedKeys(): Set<String> =
        unsavedApplicationKeys(appProvider.applicationList, builtKeys, updatedKeys)

    /**
     * Re-reads the change baselines from the (just switched or just saved) profile's stored
     * pack — every operation that changes which saved set is current ends with this.
     */
    private suspend fun loadChangeBaselines(profileId: Long) {
        invalidateChangeBaselines()
        val newBuiltKeys = appProvider.getSavedPackKeys(profileId)
        val newBuiltIconHashes = appProvider.getBuiltIconHashes(profileId)
        val newSavedIconHashes = appProvider.getSavedIconHashes(profileId)
        // A notification or another queued operation may have switched again while Room read.
        if (appProvider.activeProfileId != profileId) return
        builtKeys = newBuiltKeys
        builtIconHashes = newBuiltIconHashes
        savedIconHashes = newSavedIconHashes
        updatedKeys = emptySet()
        changeBaselinesProfileId = profileId
    }

    private suspend fun resetChangeBaselines() =
        loadChangeBaselines(appProvider.activeProfileId)

    /** Saves the active profile if asked, switches to [id], and refreshes the baselines. */
    private suspend fun performSwitch(
        id: Long,
        saveFirst: Boolean,
        saveIfChanged: Boolean = false
    ) {
        profileSessionOperations.withLock {
            // Notification-driven switches automatically preserve unsaved work. Evaluate this
            // only after waiting for an in-flight watch apply, otherwise a freshly applied icon
            // could land after the earlier check and then be discarded by this switch.
            if (saveFirst || (saveIfChanged && hasUnsavedChanges())) {
                appProvider.saveActiveProfileIcons()
            }
            invalidateChangeBaselines()
            appProvider.switchProfile(id)
            resetChangeBaselines()
        }
        refreshMissingPacks()
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

    /** Updates a profile's user-facing details (name, description, built-pack label). */
    fun updateProfileDetails(id: Long, name: String, description: String, packLabel: String) {
        viewModelScope.launch {
            appProvider.updateProfileDetails(id, name, description, packLabel)
            _toastEvents.trySend(R.string.profileUpdated)
        }
    }

    /** Deletes a profile (never the default); switches to the default first when active. */
    fun deleteProfile(id: Long) {
        viewModelScope.launch {
            appProvider.deleteProfile(id)
            resetChangeBaselines()
            _toastEvents.trySend(R.string.profileDeleted)
        }
    }

    /** True while [clearIcons] runs — the Settings row shows it and blocks re-entry. */
    var clearingIcons by mutableStateOf(false)
        private set

    /**
     * Clears every created icon (and persists the empty state). The toast is emitted here, once
     * the work is really done: Settings used to show it the moment the call was made, so a slow
     * or failing clear reported success that hadn't happened.
     */
    fun clearIcons() {
        if (clearingIcons) return
        clearingIcons = true
        viewModelScope.launch {
            try {
                appProvider.clearIcons()
                // Saved pack is now empty → reset both change baselines.
                resetChangeBaselines()
                _toastEvents.trySend(R.string.iconsCleared)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.error("MainViewModel", "Clearing icons failed", e)
                _toastEvents.trySend(R.string.iconsClearFailed)
            } finally {
                clearingIcons = false
            }
        }
    }

    // ---- Missing icon packs ---------------------------------------------------------

    /** Keys of the active profile's icons locked behind a missing pack (marks the rows). */
    val lockedIconKeys: Set<String> get() = appProvider.lockedIconKeys

    /** The active profile's locked icons grouped by missing pack — badge, banner, dialog. */
    var missingPackSummary by mutableStateOf<List<IconLockManager.MissingPack>>(emptyList())
        private set

    var showMissingPacksDialog by mutableStateOf(false)
        private set

    // Profiles already prompted this session, so switching back and forth doesn't nag.
    private val missingPacksPrompted = mutableSetOf<Long>()

    /**
     * Recomputes the summary and (when [prompt]) opens the dialog once per profile per
     * session, unless the profile opted out ("don't show again").
     */
    private suspend fun refreshMissingPacks(prompt: Boolean = true) {
        missingPackSummary = appProvider.missingPackSummary()
        if (missingPackSummary.isEmpty()) {
            showMissingPacksDialog = false
            return
        }
        if (!prompt) return
        val profileId = appProvider.activeProfileId
        if (!missingPacksPrompted.add(profileId)) return
        if (appProvider.activeProfile()?.hideMissingPackWarning == true) return
        showMissingPacksDialog = true
    }

    /** User-invoked (badge/banner tap): always opens, regardless of "don't show again". */
    fun openMissingPacksDialog() { showMissingPacksDialog = true }

    fun dismissMissingPacksDialog(dontShowAgain: Boolean) {
        showMissingPacksDialog = false
        if (dontShowAgain) viewModelScope.launch { appProvider.setHideMissingPackWarning(true) }
    }

    // ---- Backup -------------------------------------------------------------------

    /** True while a backup export/import runs — Settings ignores further taps meanwhile. */
    var backupInProgress by mutableStateOf(false)
        private set

    /**
     * Runs one backup/import operation at a time: the shared busy flag gates re-entry, a
     * failure logs and toasts [failureToast], cancellation propagates.
     */
    private fun runBackupOp(@StringRes failureToast: Int, op: suspend () -> Unit) {
        if (backupInProgress) return
        viewModelScope.launch {
            backupInProgress = true
            try {
                op()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.error("MainViewModel", "Backup operation failed", e)
                _toastEvents.trySend(failureToast)
            } finally {
                backupInProgress = false
            }
        }
    }

    /**
     * Writes the full backup (all profiles, settings, watch rules, uploads, keystore) to
     * [uri]. Unsaved work is saved first, the same way the save-before-switch prompt would —
     * exporting a backup that silently lacks what's on screen would be worse.
     */
    fun exportBackup(uri: Uri) = runBackupOp(R.string.backupExportFailed) {
        if (hasUnsavedChanges()) {
            appProvider.saveActiveProfileIcons()
            resetChangeBaselines()
            refreshMissingPacks(prompt = false)
        }
        backupManager.exportBackup(uri)
        _toastEvents.trySend(R.string.backupExported)
    }

    /**
     * Exports one profile as a shareable file. Icons embed their image data; whether
     * paid-pack icons are usable is enforced on the importing device (see BackupManager).
     */
    fun exportProfile(profileId: Long, uri: Uri) = runBackupOp(R.string.profileExportFailed) {
        if (profileId == activeProfileId && hasUnsavedChanges()) {
            appProvider.saveActiveProfileIcons()
            resetChangeBaselines()
            refreshMissingPacks(prompt = false)
        }
        backupManager.exportProfile(profileId, uri)
        _toastEvents.trySend(R.string.profileExported)
    }

    // A picked full-backup file waiting for the "replace everything?" confirmation.
    // (Profile files import right away — they only add a new profile.)
    var pendingBackupImport by mutableStateOf<Uri?>(null)
        private set

    fun confirmBackupImport() {
        val uri = pendingBackupImport ?: return
        pendingBackupImport = null
        runBackupOp(R.string.backupImportFailed) { performImport(uri) }
    }

    fun cancelBackupImport() { pendingBackupImport = null }

    /**
     * Entry point for a picked `.renkin` file: full backups are destructive, so they stop at
     * a confirmation ([pendingBackupImport]); shared profiles are additive and import now.
     */
    fun importFile(uri: Uri) = runBackupOp(R.string.backupImportFailed) {
        when (backupManager.peekKind(uri)) {
            BackupManager.ImportKind.BACKUP -> pendingBackupImport = uri
            BackupManager.ImportKind.PROFILE -> performImport(uri)
        }
    }

    private suspend fun performImport(uri: Uri) {
        val result = backupManager.importFile(uri)
        when (result.kind) {
            BackupManager.ImportKind.BACKUP -> {
                // Everything was replaced — reload the in-memory state in place.
                appProvider.reloadActiveProfile()
                resetChangeBaselines()
                // A restored profile counts as freshly entered for the prompt.
                missingPacksPrompted.remove(appProvider.activeProfileId)
                refreshMissingPacks()
                _toastEvents.trySend(R.string.backupImported)
            }
            BackupManager.ImportKind.PROFILE -> {
                // Additive: the active profile is untouched; the new one is in the switcher
                // (its missing-pack prompt fires when the user switches to it).
                _toastEvents.trySend(R.string.profileImported)
            }
        }
        // Classify any packs the import referenced (quiet best effort — retried later at
        // app start and by the periodic watch worker when offline now). Free verdicts
        // unlock icons, so reload when something got decided.
        if (runCatching { appProvider.verifyPendingVerdicts() }.getOrDefault(false)) {
            when (result.kind) {
                // The backup already replaced everything, so there is no session to keep.
                BackupManager.ImportKind.BACKUP -> {
                    appProvider.reloadActiveProfile()
                    resetChangeBaselines()
                }
                // A shared profile lands beside the active one. Its verdicts matter when that
                // profile is entered; reloading the current session here would risk discarding
                // or cross-merging unrelated unsaved work.
                BackupManager.ImportKind.PROFILE -> Unit
            }
            refreshMissingPacks(prompt = false)
        }
    }

    /** Calendar-enabled apps whose source pack is missing day drawables (shown before a build). */
    suspend fun calendarWarnings(preferences: Preferences): List<ApplicationProvider.CalendarWarning> =
        appProvider.calendarWarnings(preferences)

    /** Of [prefixes], those that are genuine calendar day-rotation sets in [packPackageName]. */
    suspend fun calendarPrefixesAmong(packPackageName: String, prefixes: List<String>): Set<String> =
        appProvider.calendarPrefixesAmong(packPackageName, prefixes)

    /** Drawable names [packPackageName] declares as live-clock icons (see ApplicationProvider). */
    suspend fun dynamicClockDrawables(packPackageName: String): Set<String> =
        appProvider.dynamicClockDrawables(packPackageName)

}
