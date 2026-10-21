package dev.renkinProject.renkin

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.renkinProject.renkin.apk.ApkUninstaller
import dev.renkinProject.renkin.apk.ApkInstallBackend
import dev.renkinProject.renkin.apk.ApkInstallResult
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.ApplicationRuntimeState
import dev.renkinProject.renkin.apk.BuiltIconPack
import dev.renkinProject.renkin.apk.PackChangeBaselines
import dev.renkinProject.renkin.apk.observePackChanges
import dev.renkinProject.renkin.apk.unsavedApplicationKeys
import dev.renkinProject.renkin.apk.UnsavedSessionMonitor
import dev.renkinProject.renkin.apk.IconGenerationService
import dev.renkinProject.renkin.apk.IconLockManager
import dev.renkinProject.renkin.apk.InstallerCatalog
import dev.renkinProject.renkin.apk.InstallerOption
import dev.renkinProject.renkin.apk.InstallerSelection
import dev.renkinProject.renkin.apk.ShizukuSupport
import dev.renkinProject.renkin.apk.installerSelection
import dev.renkinProject.renkin.apk.IconApplyOutcome
import dev.renkinProject.renkin.data.SharedIconHandoff
import dev.renkinProject.renkin.data.StoredUploadedImageLibrary
import dev.renkinProject.renkin.data.UploadedImageLibrary
import dev.renkinProject.renkin.data.readImportedText
import dev.renkinProject.renkin.icon.creator.FontLibrary
import dev.renkinProject.renkin.icon.creator.IconModifierInput
import dev.renkinProject.renkin.icon.creator.SystemFontLibrary
import dev.renkinProject.renkin.icon.creator.VerifiedModifierInput
import dev.renkinProject.renkin.icon.creator.IconGenerationResult
import dev.renkinProject.renkin.apk.IconUndoStep
import dev.renkinProject.renkin.apk.ProfileReloadFailedException
import dev.renkinProject.renkin.apk.ProfileOperationSupersededException
import dev.renkinProject.renkin.apk.ProfileOperationBusyException
import dev.renkinProject.renkin.apk.ProfileSessionChangedException
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.AppFilterNoIconKey
import dev.renkinProject.renkin.data.AppSortOrder
import dev.renkinProject.renkin.data.AppSortOrderKey
import dev.renkinProject.renkin.data.DarkMode
import dev.renkinProject.renkin.data.DarkModeKey
import dev.renkinProject.renkin.data.InstallMethod
import dev.renkinProject.renkin.data.AskInstallerEveryTimeKey
import dev.renkinProject.renkin.data.HideProfileShareWarningKey
import dev.renkinProject.renkin.data.OnboardingSeenKey
import dev.renkinProject.renkin.data.LastSeenWhatsNewVersionKey
import dev.renkinProject.renkin.data.PrimaryIconPackKey
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.setBooleanValue
import dev.renkinProject.renkin.data.setEnumValue
import dev.renkinProject.renkin.data.setIntValue
import dev.renkinProject.renkin.data.transfer.BackupManager
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.StorageRecoveryState
import dev.renkinProject.renkin.service.BackgroundWorkScheduler
import dev.renkinProject.renkin.data.transfer.AutoBackupManager
import dev.renkinProject.renkin.data.transfer.isIconPackStudioExport
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
import dev.renkinProject.renkin.util.timeProfilePhase
import dev.renkinProject.renkin.vector.SvgVectorImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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

    suspend fun previewIconWithInput(
        app: PackageInfoStruct,
        options: GenerationOptions,
        customIcon: ResourceDrawable?
    ): IconGenerationResult = IconGenerationResult(previewIcon(app, options, customIcon))

    suspend fun applyModifier(icon: IconPackDrawable, options: GenerationOptions): IconPackDrawable
}

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val appProvider: ApplicationProvider,
    private val watchRepo: WatchRepository,
    private val backupManager: BackupManager,
    autoBackupManager: AutoBackupManager,
    private val unsavedSessionMonitor: UnsavedSessionMonitor,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application), IconPreviewBuilder {
    private val installerCatalog = InstallerCatalog(application)
    val uploadedImages: UploadedImageLibrary = StoredUploadedImageLibrary(application)
    val fonts: FontLibrary = SystemFontLibrary
    /**
     * Keeps profile-session bookkeeping on the same side of a profile switch as the provider
     * operation that changed the icon. The provider separately protects its live icon list.
     */
    private val profileSessionOperations = Mutex()

    val applicationState = appProvider.applicationState
    val installedIcons = appProvider.installedIcons
    val runtimeState = appProvider.runtimeState
    /** Action-time snapshot only; composables collect [applicationState]. */
    val applicationList: List<PackageInfoStruct> get() = appProvider.applicationList

    val iconPackState = appProvider.iconPackState

    /** Action-time snapshot only; UI readiness comes from [applicationState]. */
    val applicationsLoaded: Boolean get() = appProvider.applicationsLoaded

    /** Action-time snapshot only; composables collect [runtimeState]. */
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
    private var changeBaselinesSession by mutableStateOf<ProfileSessionToken?>(null)

    /** Action-time snapshot; UI passes its collected [runtimeState] to [isProfileSummaryReady]. */
    val profileSummaryReady: Boolean
        get() = isProfileSummaryReady(runtimeState.value)

    fun isProfileSummaryReady(runtime: ApplicationRuntimeState): Boolean =
        isProfileSummaryReady(
            startupComplete = runtime.startupComplete,
            isProfileSwitching = runtime.isProfileSwitching,
            baselineProfileId = changeBaselinesSession?.takeIf { it == runtime.sessionToken }?.profileId,
            activeProfileId = runtime.activeProfileId
        )

    private fun invalidateChangeBaselines() {
        changeBaselinesSession = null
    }

    /**
     * Re-reads the baselines for [profileId] after a build. Called however the install ended:
     * producing the APK is what counts as built, so a dismissed installer must not leave the
     * change list claiming the icons are still pending.
     */
    private suspend fun syncBuildBaselines(pack: BuiltIconPack) {
        val expected = sessionToken
        if (expected.profileId != pack.profileId) return
        loadChangeBaselines(expected, clearUpdated = false)
        appProvider.commitSessionState(expected) {
            if (pack.sessionToken == expected) {
                val builtByKey = pack.profileApps.associateBy { it.key }
                val liveByKey = applicationList.associateBy { it.key }
                updatedKeys = updatedKeys.filterTo(mutableSetOf()) { liveByKey[it] != builtByKey[it] }
            }
        }
    }

    private var baselineKeys by mutableStateOf<Set<String>>(emptySet())
    /** Action-time snapshot; UI passes its collected runtime to [builtKeysFor]. */
    val builtKeys: Set<String>
        get() = builtKeysFor(runtimeState.value)

    fun builtKeysFor(runtime: ApplicationRuntimeState): Set<String> =
        if (changeBaselinesSession == runtime.sessionToken) baselineKeys
        else appProvider.loadedSavedIconKeysFor(runtime.sessionToken)

    // Keys whose icons were manually changed this session (via applyIcon with a non-null icon).
    // Distinct from builtKeys: an app in both sets had an icon already and was re-edited.
    // Reset after every successful build.
    private var sessionUpdatedKeys by mutableStateOf<Set<String>>(emptySet())
    private var updatedKeysOwner by mutableStateOf<ProfileSessionToken?>(null)
    // Switching expires these edits; refreshApps explicitly rebinds preserved edits after reload.
    var updatedKeys: Set<String>
        get() = if (updatedKeysOwner == sessionToken) sessionUpdatedKeys else emptySet()
        private set(value) {
            sessionUpdatedKeys = value
            updatedKeysOwner = sessionToken
        }

    /** Action-time snapshot only; composables collect [runtimeState]. */
    val sessionToken: ProfileSessionToken get() = appProvider.sessionToken

    private fun launchProfileOperation(
        expected: ProfileSessionToken = sessionToken,
        serialize: Boolean = true,
        operation: suspend (ProfileSessionToken) -> Unit
    ): Job {
        return viewModelScope.launch {
            suspend fun execute() {
                try {
                    operation(expected)
                } catch (error: ProfileSessionChangedException) {
                    _toastEvents.trySend(error.userMessage())
                } catch (error: ProfileOperationBusyException) {
                    _toastEvents.trySend(R.string.profileRenderBusy)
                } catch (error: ProfileOperationSupersededException) {
                    _toastEvents.trySend(R.string.profileOperationSuperseded)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.error("MainViewModel", "Profile operation failed", error)
                    _toastEvents.trySend(R.string.profileOperationFailed)
                }
            }
            if (serialize) profileSessionOperations.withLock { execute() } else execute()
        }
    }

    /** One shared diff; a newly published list cannot use another profile/session's baselines. */
    val pendingPackChanges = observePackChanges(
        applicationState,
        snapshotFlow {
            PackChangeBaselines(
                changeBaselinesSession, builtIconHashes, savedIconHashes, sessionUpdatedKeys
            ) to updatedKeysOwner
        }.combine(runtimeState) { (baselines, editsOwner), runtime ->
            baselines.copy(
                owner = baselines.owner.takeIf { runtime.ready && it == runtime.sessionToken },
                updated = baselines.updated.takeIf { editsOwner == runtime.sessionToken }.orEmpty()
            )
        },
        viewModelScope
    )

    // Set when opened from an icon-watch notification; the home screen shows the apply
    // modal for this suggestion.
    var pendingWatchSuggestionId by mutableStateOf<Long?>(null)
        private set

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
            // could apply the icon to the wrong profile. The ViewModel-scoped coroutine is
            // cancelled naturally when its owner is cleared, even if startup cannot finish.
            runtimeState.first { it.startupComplete }
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

    fun clearPendingWatchSuggestion(expectedId: Long) {
        if (pendingWatchSuggestionId == expectedId) pendingWatchSuggestionId = null
    }

    // Label of an external icon pack installed while the app was open; non-null drives a
    // dialog prompting to reload so the new pack appears among the available sources.
    var newIconPackInstalled by mutableStateOf<String?>(null)
        private set

    // Packs we've already prompted for this session, so dismissing the dialog doesn't make
    // it pop again on every return to the foreground.
    private val promptedIconPacks = mutableSetOf<String>()

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
    private val _installToastEvents = Channel<String>(Channel.BUFFERED)
    val installToastEvents = _installToastEvents.receiveAsFlow()

    /**
     * A change that can still be taken back: [messageRes] describes it, [count] fills its
     * plural. Shown as a snackbar with an Undo action rather than a toast, because a toast
     * cannot be acted on.
     */
    data class UndoPrompt(
        @androidx.annotation.StringRes val messageRes: Int,
        val session: ProfileSessionToken,
        val step: IconUndoStep
    )

    // CONFLATED, unlike the toast channel: only one change is undoable at a time, so a queue of
    // older prompts would offer to undo something the tracker has already forgotten.
    private val _undoEvents = Channel<UndoPrompt>(Channel.CONFLATED)
    val undoEvents = _undoEvents.receiveAsFlow()
    val undoStepState = applicationState.map { it.undoStep }.distinctUntilChanged()
    /** Action-time snapshot only; UI observes long-operation status through [applicationState]. */
    val longOperationRunning: Boolean get() = appProvider.longOperationRunning

    private val foregroundProfileActions = ForegroundProfileActions(profileSessionOperations) {
        _toastEvents.trySend(R.string.profileActionBusy)
    }
    val profileActionProgress = foregroundProfileActions.progress

    private fun launchVisibleProfileOperation(
        @androidx.annotation.StringRes messageRes: Int,
        expected: ProfileSessionToken = sessionToken,
        applicationName: String? = null,
        serialize: Boolean = true,
        operation: suspend (ProfileSessionToken) -> Unit
    ) {
        val progress = ProfileActionProgress(messageRes, applicationName)
        foregroundProfileActions.launch(progress, serialize) {
            launchProfileOperation(expected, serialize = false) { owner ->
                try {
                    operation(owner)
                } catch (_: ProfileOperationBusyException) {
                    _toastEvents.trySend(R.string.profileActionBusy)
                }
            }
        }
    }

    private fun offerUndo(@androidx.annotation.StringRes messageRes: Int, expected: ProfileSessionToken, step: IconUndoStep) {
        // Presentation reads the completed aggregate; it must not wait for another gate after
        // the mutation already finished. The collector and Undo action revalidate stale offers.
        val state = applicationState.value
        if (state.sessionToken == expected && state.undoStep?.operationId == step.operationId) {
            _undoEvents.trySend(UndoPrompt(messageRes, expected, step))
        }
    }

    fun undoLastIconChange(expected: ProfileSessionToken, step: IconUndoStep) {
        launchVisibleProfileOperation(R.string.undoInProgress, expected) {
            if (appProvider.undoLastIconChange(expected, step)) {
                _toastEvents.trySend(R.string.undoRestored)
            } else {
                _toastEvents.trySend(R.string.undoUnavailable)
            }
        }
    }

    private var currentStateReloadRequired = false

    private fun loadStartup() {
        if (startupLoading || (profileSummaryReady && !startupFailed)) return
        startupLoading = true
        startupFailed = false
        viewModelScope.launch {
            try {
                profileSessionOperations.withLock {
                    if (currentStateReloadRequired && appProvider.startupComplete) appProvider.replaceProfileSession { }
                    else appProvider.ensureInitialized()
                    resetChangeBaselines()
                    refreshMissingPacks()
                    currentStateReloadRequired = false
                }
                // Classify any source packs that still lack a paid/free verdict (quiet best
                // effort; imported-offline icons stay locked until a lookup succeeds). A verdict
                // becoming decisive can unlock icons without dropping unsaved session edits.
                if (runCatching { appProvider.verifyPendingVerdicts() }.getOrDefault(false)) {
                    profileSessionOperations.withLock {
                        appProvider.refreshIconLocks(sessionToken)
                        refreshMissingPacks(prompt = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProfileSessionChangedException) {
                _toastEvents.trySend(e.userMessage())
            } catch (e: Exception) {
                startupFailed = true
                Log.error("MainViewModel", "Startup loading failed", e)
            } finally {
                startupLoading = false
            }
        }
    }

    fun retryStartup() = loadStartup()

    var isRefreshing by mutableStateOf(false)
        private set
    private var refreshJob: Job? = null
    private var refreshSession: ProfileSessionToken? = null
    @Volatile private var refreshRequest: Any? = null
    var refreshProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    private var baselineRefreshJob: Job? = null
    private var baselineRefreshSession: ProfileSessionToken? = null
    @Volatile private var baselineLoadOwner: Any? = null

    private fun requestBaselineRefresh(expected: ProfileSessionToken) {
        if (!appProvider.isCurrentSession(expected) || changeBaselinesSession == expected ||
            (baselineRefreshSession == expected && baselineRefreshJob?.isActive == true)) return
        baselineRefreshJob?.cancel()
        baselineRefreshSession = expected
        baselineRefreshJob = viewModelScope.launch {
            if (appProvider.isCurrentSession(expected) && changeBaselinesSession != expected) {
                try {
                    // Only publication takes the UI-operation mutex; edits can run during hashing.
                    loadChangeBaselines(expected, clearUpdated = false, serializePublication = true)
                    profileSessionOperations.withLock {
                        if (!appProvider.isCurrentSession(expected)) return@withLock
                        refreshMissingPacks(prompt = false)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ProfileSessionChangedException) {
                    // A newer ready session will be delivered by the session observer.
                } catch (error: Exception) {
                    Log.error("MainViewModel", "Profile baselines could not be loaded", error)
                    _toastEvents.trySend(R.string.profileOperationFailed)
                }
            }
        }
    }

    private fun observeProfileSession() {
        viewModelScope.launch {
            runtimeState.map { runtime -> runtime.sessionToken.takeIf { runtime.ready } }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { expected ->
                    if (refreshSession != null && refreshSession != expected) cancelRefresh()
                    requestBaselineRefresh(expected)
                }
        }
    }

    init {
        unsavedSessionMonitor.start()
        loadStartup()
        observeProfileSession()
        loadWhatsNewState()
    }

    private fun cancelRefresh() {
        if (refreshRequest != null) _toastEvents.trySend(R.string.refreshCancelled)
        refreshJob?.cancel()
        refreshJob = null
        refreshSession = null
        refreshRequest = null
        refreshProgress = null
        isRefreshing = false
    }

    fun refresh() = refresh(sessionToken)

    private fun refresh(expected: ProfileSessionToken) {
        if (isRefreshing) return
        // Set synchronously so a second tap cannot enqueue another refresh before the coroutine
        // starts, and so the top-bar action immediately communicates that work is running.
        val request = Any()
        refreshRequest = request
        refreshSession = expected
        isRefreshing = true
        refreshJob = launchProfileOperation(expected, serialize = false) {
            try {
                val preferences = getApplication<Application>().dataStore
                    .getPreferencesAfterPendingWrites()
                val iconPackageName = preferences.getStringValue(PrimaryIconPackKey)
                if (!appProvider.iconPackLoaded && iconPackageName.isNotEmpty()) {
                    _toastEvents.trySend(R.string.syncText)
                    return@launchProfileOperation
                }
                // Icons whose real origin (via an own pack used as source) isn't owned on
                // this device are withheld — say so instead of leaving silent empty slots.
                val result = appProvider.refreshIcons(expected) { done, total ->
                    if (refreshRequest === request) refreshProgress = done to total
                }
                appProvider.commitSessionState(expected) {
                    if (result.withheldCount > 0) _toastEvents.trySend(R.string.refreshLockedSkipped)
                    if (result.conflictedCount > 0) _toastEvents.trySend(R.string.refreshNewerEditsKept)
                    if (result.missingCount > 0) _toastEvents.trySend(R.string.refreshTargetsGone)
                    result.undoStep?.takeIf(appProvider::ownsUndo)?.let { step ->
                        _undoEvents.trySend(UndoPrompt(R.string.undoIconsRefreshed, expected, step))
                    }
                }
            } finally {
                if (refreshRequest === request) {
                    refreshJob = null
                    refreshSession = null
                    refreshRequest = null
                    refreshProgress = null
                    isRefreshing = false
                }
            }
        }
    }

    fun selectPrimarySource(source: Source, packageName: String?) {
        launchProfileOperation { expected ->
            val selectedSession = appProvider.selectPrimarySource(expected, source, packageName)
            if (source != Source.NONE) refresh(selectedSession)
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

    /** Unsaved work a previous process held when Android ended it; offered or explained after startup. */
    val lostUnsavedWork = unsavedSessionMonitor.lostWork

    /** OK on the notice, or Not now on the restore offer: the journal stays for a later start. */
    fun dismissLostUnsavedWork() = unsavedSessionMonitor.dismiss()

    fun discardLostIcons() {
        viewModelScope.launch { unsavedSessionMonitor.closeOffer() }
    }

    /** Restores the offered hand-made icons; with [refreshAfter] also regenerates refresh output. */
    fun restoreLostIcons(refreshAfter: Boolean) {
        val offer = lostUnsavedWork.value?.offer ?: return
        unsavedSessionMonitor.dismiss()
        launchProfileOperation { expected ->
            val restored = appProvider.restoreUnsavedIcons(expected, offer)
            appProvider.commitSessionState(expected) { updatedKeys = updatedKeys + restored }
            unsavedSessionMonitor.closeOffer()
            _toastEvents.trySend(if (restored.isEmpty()) R.string.lostIconsNothingRestored else R.string.lostIconsRestored)
            if (refreshAfter) refresh(expected)
        }
    }

    fun refreshAfterLostSession() {
        val lost = lostUnsavedWork.value?.marker
        unsavedSessionMonitor.dismiss()
        if (lost != null && lost.profileId == activeProfileId && startupComplete) refresh()
    }

    /** The app is leaving the foreground: write journaled icons without waiting for the delay. */
    fun flushUnsavedJournal() = unsavedSessionMonitor.flush()

    var whatsNewVisible by mutableStateOf(false)
        private set

    private fun loadWhatsNewState() {
        viewModelScope.launch {
            StorageOperationGate.recoveryState.first { it == StorageRecoveryState.READY }
            try {
                StorageOperationGate.run {
                    val store = getApplication<Application>().dataStore
                    val preferences = store.getPreferencesAfterPendingWrites()
                    val lastSeen = preferences[LastSeenWhatsNewVersionKey]
                    if (shouldShowWhatsNew(
                            lastSeenVersion = lastSeen,
                            onboardingSeen = preferences.getBooleanValue(OnboardingSeenKey)
                        )
                    ) {
                        whatsNewVisible = true
                    } else if (lastSeen == null) {
                        store.setIntValue(LastSeenWhatsNewVersionKey, LATEST_WHATS_NEW_VERSION_CODE)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.error("MainViewModel", "Could not load release notes state", error)
            }
        }
    }

    fun dismissWhatsNew() {
        whatsNewVisible = false
        updatePreferences {
            setIntValue(LastSeenWhatsNewVersionKey, LATEST_WHATS_NEW_VERSION_CODE)
        }
    }

    fun setDarkMode(mode: DarkMode) = updatePreferences {
        setEnumValue(DarkModeKey, mode)
    }

    fun setInstallMethod(method: InstallMethod, externalComponent: String = "") = updatePreferences {
        persistInstallerSelection(InstallerSelection(method, externalComponent))
    }

    fun setAskInstallerEveryTime(enabled: Boolean) = updatePreferences {
        setBooleanValue(AskInstallerEveryTimeKey, enabled)
    }

    /** A content URI an external editor can read [bitmap] from, or null when it cannot be written. */
    suspend fun shareIconForEditing(bitmap: Bitmap): Uri? = SharedIconHandoff.write(getApplication(), bitmap)

    /** A picked SVG file parsed for the vector editor, or null when it is unreadable or invalid. */
    suspend fun importSvg(uri: Uri): SvgVectorImporter.ImportedSvg? = withContext(Dispatchers.IO) {
        runCatching { readImportedText(getApplication(), uri) }.getOrNull()?.let(SvgVectorImporter::parse)
    }

    suspend fun installerEntries(): List<InstallerOption> = withContext(Dispatchers.IO) {
        installerCatalog.entries()
    }

    suspend fun installerLabel(selection: InstallerSelection): String? = withContext(Dispatchers.IO) {
        installerCatalog.labelFor(selection)
    }

    var shizukuState by mutableStateOf(ShizukuSupport.state(application))
        private set

    // Binder and permission callbacks cover Shizuku starting, stopping and answering the prompt.
    private val shizukuObservation = ShizukuSupport.observe(::refreshShizukuState)

    // Installing Shizuku sends no callback, so the picker refreshes whenever it is shown.
    fun refreshShizukuState() {
        shizukuState = ShizukuSupport.state(getApplication<Application>())
    }

    fun requestShizukuPermission() {
        if (!ShizukuSupport.requestPermission(getApplication<Application>())) refreshShizukuState()
    }

    fun openShizukuManager(): Boolean = ShizukuSupport.openManager(getApplication<Application>())

    override fun onCleared() {
        backupCoordinator.close()
        shizukuObservation.close()
        buildCoordinator.close()
        super.onCleared()
    }

    fun hideProfileShareWarning() = updatePreferences {
        setBooleanValue(HideProfileShareWarningKey, true)
    }

    private fun updatePreferences(
        block: suspend DataStore<Preferences>.() -> Unit
    ) {
        viewModelScope.launch { getApplication<Application>().dataStore.block() }
    }

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

    private val buildCoordinator = BuildInstallCoordinator(
        application = getApplication(),
        provider = appProvider,
        scope = viewModelScope,
        syncBaselines = ::syncBuildBaselines,
        refreshMissingPacks = { refreshMissingPacks(prompt = false) },
        notify = { _toastEvents.trySend(it) },
        notifyText = { _installToastEvents.trySend(it) },
        pendingApkExport = PendingApkExportState(savedStateHandle)
    )

    internal val buildState: BuildInstallState get() = buildCoordinator.state
    val buildStep: String? get() = when (val current = buildState) {
        is BuildInstallState.Building -> current.step
        is BuildInstallState.Installing -> current.route?.let { route ->
            getApplication<Application>().getString(when {
                route.backend == ApkInstallBackend.EXTERNAL -> R.string.buildOpeningInstaller
                current.request.wasUpdate -> R.string.buildUpdatingVia
                else -> R.string.buildInstallingVia
            }, route.label)
        } ?: getApplication<Application>().getString(when {
            current.replacing -> R.string.buildReplacing
            current.request.wasUpdate -> R.string.buildUpdating
            else -> R.string.buildInstalling
        })
        else -> null
    }
    val buildProgress: Pair<Int, Int>? get() = (buildState as? BuildInstallState.Building)?.progress
    val buildBusy: Boolean get() = buildCoordinator.busy

    enum class BuildOutcome { FIRST_INSTALL, UPDATE }
    data class BuildOutcomeInfo(val outcome: BuildOutcome, val packLabel: String, val installerLabel: String,
        val savingProfile: Boolean, val profileSaveFailed: Boolean)
    val buildOutcome: BuildOutcomeInfo? get() = (buildState as? BuildInstallState.Done)?.let {
        BuildOutcomeInfo(if (it.wasUpdate) BuildOutcome.UPDATE else BuildOutcome.FIRST_INSTALL,
            it.packLabel, it.installerLabel, it.savingProfile, it.profileSaveFailed)
    }
    fun dismissBuildOutcome() = buildCoordinator.dismissBuildOutcome()

    data class InstallFailureInfo(val summary: String, val report: String, val missingSystemInstaller: Boolean,
        val canSaveApk: Boolean)
    val installFailure: InstallFailureInfo? get() = (buildState as? BuildInstallState.Failed)?.let {
        InstallFailureInfo(it.summary, it.report, it.result == ApkInstallResult.NO_SYSTEM_INSTALLER, it.pack != null)
    }
    fun dismissInstallFailure() = buildCoordinator.dismissInstallFailure()
    fun beginFailedApkExport(openPicker: (String) -> Unit) = buildCoordinator.beginFailedApkExport(openPicker)
    fun finishFailedApkExport(target: Uri?) = buildCoordinator.finishFailedApkExport(target)

    val installerPromptReason: InstallerChoiceReason? get() = (buildState as? BuildInstallState.ChoosingInstaller)?.reason
    fun confirmInstallerSelection(selection: InstallerSelection) = buildCoordinator.confirmInstallerSelection(selection)
    fun dismissInstallerSelection() = buildCoordinator.dismissInstallerSelection()
    val installFallbackPending: Boolean get() = buildState is BuildInstallState.Conflict
    val installFallbackSignatureMismatch: Boolean get() = (buildState as? BuildInstallState.Conflict)?.signatureMismatch == true
    fun confirmInstallFallback() = buildCoordinator.confirmInstallFallback()
    fun dismissInstallFallback() = buildCoordinator.dismissInstallFallback()

    fun build(expected: ProfileSessionToken) {
        if (buildCoordinator.busy) {
            _toastEvents.trySend(R.string.buildOperationPending)
            return
        }
        if (longOperationRunning) {
            _toastEvents.trySend(R.string.profileRenderBusy)
            return
        }
        if (!startupComplete || isProfileSwitching) {
            _toastEvents.trySend(R.string.profileStillLoading)
            return
        }
        if (!appProvider.isCurrentSession(expected)) {
            _toastEvents.trySend(expected.expiredSessionMessage(sessionToken))
            return
        }
        buildCoordinator.build(expected)
    }

    // Records that [app] was hand-edited this session (so the build preview marks it "changed").
    // Clearing an icon (icon == null) is a removal, not a change, so it isn't recorded.
    private suspend fun markUpdated(expected: ProfileSessionToken, app: PackageInfoStruct, icon: IconPackDrawable?): Boolean =
        appProvider.commitSessionState(expected) {
            if (icon != null && appProvider.applicationList.any { it.key == app.key && it.baseIcon === icon }) {
                updatedKeys = updatedKeys + app.key
            }
        }

    /**
     * Regenerates one app's icon with the current recipe — the quick action for "this one came
     * out wrong" without refreshing the whole list.
     */
    fun refreshSingleIcon(app: PackageInfoStruct, expected: ProfileSessionToken) {
        launchVisibleProfileOperation(
            R.string.iconGenerationInProgress, expected, app.appName, serialize = false
        ) {
            val step = appProvider.refreshIcon(expected, app) ?: return@launchVisibleProfileOperation
            offerUndo(R.string.undoIconRefreshed, expected, step)
        }
    }

    /**
     * Same assignment as [applyIcon], but awaited and reporting its outcome. The icon-watch
     * apply modal deletes the rule (which cascades the suggestion) once the icon is applied —
     * fire-and-forget would drop the rule even when the target row was gone and nothing landed.
     */
    suspend fun applyWatchIcon(
        app: PackageInfoStruct,
        icon: IconPackDrawable,
        sourcePackName: String?,
        expected: ProfileSessionToken
    ): IconApplyOutcome {
        return try {
            profileSessionOperations.withLock {
                val outcome = appProvider.applyWatchIcon(expected, app, icon, sourcePackName)
                if (outcome == IconApplyOutcome.APPLIED) {
                    // A later session change can skip UI bookkeeping, but cannot undo this result.
                    try {
                        markUpdated(expected, app, icon)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.error("MainViewModel", "Could not update watched icon bookkeeping", error)
                    }
                }
                outcome
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("MainViewModel", "Could not apply watched icon", error)
            IconApplyOutcome.FAILED
        }
    }

    /**
     * Restores one app to its launcher default. Unlike assigning a null icon generically, this
     * also discards an imported row held behind a missing pack and clears calendar rotation.
     */
    fun resetIcon(app: PackageInfoStruct, expected: ProfileSessionToken) {
        launchVisibleProfileOperation(R.string.iconResetInProgress, expected, app.appName) {
            val step = appProvider.resetIcon(expected, app) ?: return@launchVisibleProfileOperation
            offerUndo(R.string.undoIconReset, expected, step)
            launchProfileOperation(expected, serialize = false) { refreshMissingPacks(prompt = false) }
        }
    }

    /**
     * Applies the icon and the calendar selection together. The edit dialog tracks the
     * calendar prefix/source-pack in local state as the user browses; persisting only the
     * icon alone would leave the stored `<calendar>` prefix pointing at the
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
        sourceUrl: String? = null,
        expected: ProfileSessionToken,
        modifierRecipe: String? = null,
        modifierInput: IconModifierInput? = null
    ) = applyPickedIcon(expected, app, icon, sourcePackName) { live, origin, rendered ->
        live.changeExport(
            rendered, sourcePackName = origin, isRefreshMade = false, isCustom = true,
            isLegacy = false, baseIcon = icon, sourceUrl = sourceUrl,
            modifierRecipe = modifierRecipe, modifierInput = modifierInput
        ).changeCalendar(calendarEnabled, calendarPrefix, calendarPackName)
    }

    /**
     * The provider owns provenance and commits the reviewed icon under its session gate.
     * Bookkeeping uses the same token after returning to Main, before a local switch can run.
     */
    private fun applyPickedIcon(
        expected: ProfileSessionToken,
        app: PackageInfoStruct,
        icon: IconPackDrawable?,
        sourcePackName: String?,
        edit: (live: PackageInfoStruct, origin: String?, rendered: IconPackDrawable?) -> PackageInfoStruct
    ) {
        launchProfileOperation(expected, serialize = false) {
            when (appProvider.applyPickedIcon(expected, app, icon, sourcePackName, edit)) {
                IconApplyOutcome.APPLIED -> markUpdated(expected, app, icon)
                IconApplyOutcome.LOCKED -> _toastEvents.trySend(R.string.iconOriginLocked)
                IconApplyOutcome.PROFILE_CHANGED -> _toastEvents.trySend(expected.expiredSessionMessage(sessionToken))
                IconApplyOutcome.TARGET_GONE -> _toastEvents.trySend(R.string.iconEditTargetGone)
                IconApplyOutcome.FAILED -> _toastEvents.trySend(R.string.profileOperationFailed)
            }
        }
    }

    /**
     * Called when the Global options activity returns: the work happened on the shared
     * provider (via GlobalOptionsViewModel), so this only refreshes the session bookkeeping —
     * hand-edited keys count as this session's edits, and a Save (which persisted the
     * profile) moves the change baselines exactly like a save-before-switch does.
     */
    fun onGlobalOptionsClosed(expected: ProfileSessionToken, editedKeys: Set<String>, applied: Boolean) {
        launchProfileOperation(expected) {
            if (!appProvider.commitSessionState(expected) { updatedKeys = updatedKeys + editedKeys }) {
                return@launchProfileOperation
            }
            if (applied) {
                loadChangeBaselines(expected)
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

    private suspend fun isIconPackInstalled(packageName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { getApplication<Application>().packageManager.getPackageInfo(packageName, 0) }.isSuccess
    }

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
        launchProfileOperation { expected ->
            try {
                appProvider.forceSync(expected)
                packBrowserPreviews.clear()
                // The sync may have unlocked held-back icons — the badge/banner must follow.
                refreshMissingPacks(prompt = false)
                _toastEvents.trySend(R.string.packsSynced)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProfileSessionChangedException) {
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
        appsRefreshing = true
        launchProfileOperation { expected ->
            val previousEdits = updatedKeys
            val previousBaselines = changeBaselinesSession
            try {
                val reloaded = appProvider.reloadPreservingSession(expected, unsavedKeys())
                packBrowserPreviews.clear()
                // An uninstalled app no longer has a visible row to carry a session edit.
                val liveKeys = appProvider.applicationList.mapTo(mutableSetOf()) { it.key }
                appProvider.commitSessionState(reloaded) {
                    updatedKeys = previousEdits.filterTo(mutableSetOf()) { it in liveKeys }
                    changeBaselinesSession = reloaded.takeIf { previousBaselines == expected }
                }
                _toastEvents.trySend(R.string.appListRefreshed)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProfileSessionChangedException) {
                throw error
            } catch (error: ProfileReloadFailedException) {
                appProvider.commitSessionState(error.restoredSession) {
                    updatedKeys = previousEdits
                    if (previousBaselines == expected) changeBaselinesSession = error.restoredSession
                }
                throw error
            } finally {
                appsRefreshing = false
            }
        }
    }

    /** Builds a preview icon for [app] from [options] (optionally a specific pack pick). */
    override suspend fun previewIcon(
        app: PackageInfoStruct,
        options: GenerationOptions,
        customIcon: ResourceDrawable?
    ): IconPackDrawable? = appProvider.getIcon(app, options, customIcon)

    override suspend fun previewIconWithInput(
        app: PackageInfoStruct,
        options: GenerationOptions,
        customIcon: ResourceDrawable?
    ): IconGenerationResult = appProvider.getIconPreview(app, options, customIcon)

    internal suspend fun previewIconWithOwnedFont(
        app: PackageInfoStruct, options: GenerationOptions, customIcon: ResourceDrawable?,
        typeface: Typeface
    ): IconGenerationResult = appProvider.getIconPreview(app, options, customIcon, typeface)

    internal suspend fun prepareModifierReplay(app: PackageInfoStruct, expected: ProfileSessionToken) =
        appProvider.prepareModifierReplay(app, expected)

    internal suspend fun renderModifierReplay(
        replay: VerifiedModifierInput,
        options: GenerationOptions
    ): IconGenerationResult = replay.render(options)

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

    /** All profiles for the switcher (default first). */
    val profiles = appProvider.profilesFlow()

    /** Action-time snapshot only; composables collect [runtimeState]. */
    val activeProfileId: Long get() = appProvider.activeProfileId

    /** Action-time snapshot only; composables collect [runtimeState]. */
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
        unsavedApplicationKeys(appProvider.applicationList, builtKeys, updatedKeys) + appProvider.unsavedIconKeys

    /**
     * Re-reads the change baselines from the (just switched or just saved) profile's stored
     * pack — every operation that changes which saved set is current ends with this.
     */
    private suspend fun loadChangeBaselines(
        expected: ProfileSessionToken,
        clearUpdated: Boolean = true,
        serializePublication: Boolean = false
    ) {
        val owner = Any()
        suspend fun begin(): Boolean = appProvider.commitSessionState(expected) {
            baselineLoadOwner = owner
            invalidateChangeBaselines()
        }
        val started = if (serializePublication) {
            profileSessionOperations.withLock {
                if (changeBaselinesSession == expected) false else begin()
            }
        } else begin()
        if (!started) return
        repeat(MAX_BASELINE_READ_ATTEMPTS) {
            if (baselineLoadOwner !== owner || !appProvider.isCurrentSession(expected)) return
            val read = timeProfilePhase("baselines profile=${expected.profileId}") {
                appProvider.readIconChangeBaselines(expected)
            }
            suspend fun publish(): Boolean = appProvider.commitIconChangeBaselines(read) { baselines ->
                if (baselineLoadOwner === owner) {
                    baselineKeys = baselines.saved.keys.toSet()
                    builtIconHashes = baselines.built
                    savedIconHashes = baselines.saved
                    if (clearUpdated) updatedKeys = emptySet()
                    changeBaselinesSession = expected
                }
            }
            val published = if (serializePublication) {
                profileSessionOperations.withLock { publish() }
            } else publish()
            if (published) return
            // Save/build changed the captured data. Read again unless a newer load superseded us.
        }
        // Keep the diff unready; a new session or save/build synchronization starts a fresh load.
        if (BuildConfig.DEBUG && baselineLoadOwner === owner && appProvider.isCurrentSession(expected)) {
            Log.debug("ProfilePerformance", "Baseline read deferred after $MAX_BASELINE_READ_ATTEMPTS attempts profile=${expected.profileId}")
        }
    }

    private suspend fun resetChangeBaselines() =
        loadChangeBaselines(sessionToken)

    /** Saves and loads the target; the session-owned diff is refreshed without holding the dialog. */
    private suspend fun performSwitch(
        id: Long,
        saveFirst: Boolean,
        saveIfChanged: Boolean = false
    ) {
        // A previous profile's optional diff must not queue ahead of another explicit switch.
        baselineRefreshJob?.cancel()
        val waitStarted = android.os.SystemClock.elapsedRealtime()
        profileSessionOperations.withLock {
            if (BuildConfig.DEBUG) {
                Log.debug("ProfilePerformance", "switch ViewModel wait elapsedMs=${android.os.SystemClock.elapsedRealtime() - waitStarted}")
            }
            if (id == activeProfileId) return@withLock
            // The provider rechecks committed dirty keys under its gate, including an edit
            // that lands between this UI check and the actual notification-driven switch.
            val shouldSave = saveFirst || (saveIfChanged && hasUnsavedChanges())
            val previous = sessionToken
            val switched = appProvider.switchProfile(id, saveFirst = shouldSave, saveIfChanged = saveIfChanged)
            if (switched != previous) {
                cancelRefresh()
                appProvider.commitSessionState(switched) {
                    invalidateChangeBaselines()
                    if (updatedKeysOwner != switched) updatedKeys = emptySet()
                }
            }
            timeProfilePhase("switch missing-pack summary") { refreshMissingPacks() }
        }
        requestBaselineRefresh(sessionToken)
    }

    /** Switches the active profile (prefs snapshot + icon set swap), optionally saving first. */
    fun switchProfile(id: Long, saveFirst: Boolean = false) {
        if (id == activeProfileId) return
        launchVisibleProfileOperation(
            if (saveFirst) R.string.profileSaveAndSwitchInProgress else R.string.profileSwitchInProgress,
            serialize = false
        ) { performSwitch(id, saveFirst) }
    }

    /** Creates a profile and switches straight to it, optionally saving the current one first. */
    fun createProfile(name: String, description: String, packLabel: String, saveFirst: Boolean = false) {
        launchVisibleProfileOperation(
            if (saveFirst) R.string.profileSaveAndSwitchInProgress else R.string.profileSwitchInProgress,
            serialize = false
        ) {
            baselineRefreshJob?.cancel()
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
        launchProfileOperation {
            val previous = sessionToken
            appProvider.deleteProfile(id)
            if (previous != sessionToken) resetChangeBaselines()
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
        launchProfileOperation { expected ->
            try {
                val cleared = appProvider.clearIcons(expected)
                // Saved pack is now empty → reset both change baselines.
                loadChangeBaselines(cleared)
                _toastEvents.trySend(R.string.iconsCleared)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProfileSessionChangedException) {
                throw e
            } catch (e: Exception) {
                Log.error("MainViewModel", "Clearing icons failed", e)
                _toastEvents.trySend(R.string.iconsClearFailed)
            } finally {
                clearingIcons = false
            }
        }
    }

    /** The active profile's locked icons grouped by missing pack — badge, banner, dialog. */
    var missingPackSummary by mutableStateOf<List<IconLockManager.MissingPack>>(emptyList())
        private set

    var showMissingPacksDialog by mutableStateOf(false)
        private set

    // Profiles already prompted this session, so switching back and forth doesn't nag.
    private val missingPacksPrompted = mutableSetOf<Long>()
    private var missingPacksOwner: ProfileSessionToken? = null

    /**
     * Recomputes the summary and (when [prompt]) opens the dialog once per profile per
     * session, unless the profile opted out ("don't show again").
     */
    private suspend fun refreshMissingPacks(prompt: Boolean = true) {
        val expected = sessionToken
        val status = try {
            appProvider.missingPackStatus(expected)
        } catch (error: ProfileSessionChangedException) {
            return
        }
        appProvider.commitSessionState(expected) {
            missingPacksOwner = expected
            missingPackSummary = status.packs
            if (status.packs.isEmpty()) {
                showMissingPacksDialog = false
            } else if (prompt && missingPacksPrompted.add(expected.profileId)) {
                showMissingPacksDialog = !status.hideWarning
            }
        }
    }

    /** User-invoked (badge/banner tap): always opens, regardless of "don't show again". */
    fun openMissingPacksDialog() { showMissingPacksDialog = true }

    fun dismissMissingPacksDialog(dontShowAgain: Boolean) {
        showMissingPacksDialog = false
        val expected = missingPacksOwner ?: return
        if (dontShowAgain) launchProfileOperation(expected) {
            appProvider.setHideMissingPackWarning(expected, true)
        }
    }

    /**
     * Uses the live list for the active profile so an unsaved IPS selection is not missed;
     * inactive profiles can be read from their persisted rows.
     */
    suspend fun profileUsesIconPackStudio(profileId: Long): Boolean =
        if (profileId == activeProfileId && applicationsLoaded) {
            applicationList.any { isIconPackStudioExport(it.sourcePackName.orEmpty()) }
        } else {
            backupManager.profileUsesIconPackStudio(profileId)
        }

    /** Full-backup counterpart: live active-profile rows plus every other saved profile. */
    suspend fun backupUsesIconPackStudio(): Boolean {
        val activeProfileUsesIps = if (applicationsLoaded) {
            applicationList.any { isIconPackStudioExport(it.sourcePackName.orEmpty()) }
        } else {
            backupManager.profileUsesIconPackStudio(activeProfileId)
        }
        return activeProfileUsesIps || backupManager.otherProfilesUseIconPackStudio(activeProfileId)
    }

    private val backupCoordinator = BackupUiCoordinator(
        provider = appProvider,
        backupManager = backupManager,
        autoBackupManager = autoBackupManager,
        scope = viewModelScope,
        profileSessionOperations = profileSessionOperations,
        saveBeforeExport = ::saveBeforeBackupExport,
        onRestored = ::onBackupRestored,
        onVerdictsUpdated = ::onBackupVerdictsUpdated,
        notify = { _toastEvents.trySend(it) }
    )

    val backupInProgress: Boolean get() = backupCoordinator.backupInProgress
    val autoBackupIntervalHours get() = backupCoordinator.autoBackupIntervalHours
    val autoBackupTreeUri get() = backupCoordinator.autoBackupTreeUri
    val lastAutoBackupAt get() = backupCoordinator.lastAutoBackupAt
    val previousStateAvailable get() = backupCoordinator.previousStateAvailable
    val previousStateStored get() = backupCoordinator.previousStateStored
    val abandonedRecoveryAvailable get() = backupCoordinator.abandonedRecoveryAvailable
    val backupRestoreStage get() = backupCoordinator.backupRestoreStage
    val backupSnapshotStage get() = backupCoordinator.backupSnapshotStage
    val backupRestoreCancellable get() = backupCoordinator.backupRestoreCancellable
    val backupCancellationRequested: Boolean get() = backupCoordinator.backupCancellationRequested
    val pendingBackupImport: BackupImportPreview? get() = backupCoordinator.pendingBackupImport

    fun setAutoBackupInterval(hours: Int) = backupCoordinator.setAutoBackupInterval(hours)
    fun setAutoBackupFolder(uri: Uri, intervalHours: Int) = backupCoordinator.setAutoBackupFolder(uri, intervalHours)
    suspend fun autoBackupFolderName(uri: Uri): String? = backupCoordinator.autoBackupFolderName(uri)
    fun cancelBackupPreparation() = backupCoordinator.cancelBackupPreparation()

    var storageRecoveryRetryRunning by mutableStateOf(false)
        private set
    var recoveryActionError by mutableStateOf<Int?>(null)
        private set

    fun retryStorageRecovery() {
        if (storageRecoveryRetryRunning) return
        storageRecoveryRetryRunning = true
        recoveryActionError = null
        viewModelScope.launch {
            try {
                profileSessionOperations.withLock {
                    StorageOperationGate.retryRecovery {
                        withContext(Dispatchers.Main) {
                            if (appProvider.startupComplete) appProvider.replaceProfileSession { }
                            else appProvider.ensureInitialized()
                            resetChangeBaselines()
                            startupFailed = false
                            refreshMissingPacks()
                        }
                    }
                    refreshBackgroundSchedules()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.error("MainViewModel", "Storage recovery retry failed", error)
                recoveryActionError = R.string.backupRecoveryRetryFailed
            } finally { storageRecoveryRetryRunning = false }
        }
    }

    fun abandonStorageRecovery() {
        if (storageRecoveryRetryRunning) return
        storageRecoveryRetryRunning = true
        recoveryActionError = null
        viewModelScope.launch {
            try {
                profileSessionOperations.withLock {
                    StorageOperationGate.abandonRecovery {
                        backupManager.abandonPendingRecovery()
                        currentStateReloadRequired = true
                        try {
                            if (appProvider.startupComplete) appProvider.replaceProfileSession { }
                            else appProvider.ensureInitialized()
                            resetChangeBaselines()
                            startupFailed = false
                            refreshMissingPacks()
                            currentStateReloadRequired = false
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) {
                            startupFailed = true
                            Log.error("MainViewModel", "Current data could not be loaded after abandoning recovery", error)
                        }
                    }
                    refreshBackgroundSchedules()
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                Log.error("MainViewModel", "Could not abandon storage recovery", error)
                recoveryActionError = R.string.backupRecoveryKeepCurrentFailed
            }
            finally { storageRecoveryRetryRunning = false }
        }
    }

    fun discardPreviousState() = backupCoordinator.discardPreviousState()
    fun discardAbandonedRecovery() = backupCoordinator.discardAbandonedRecovery()
    fun restorePreviousState() = backupCoordinator.restorePreviousState()
    fun exportBackup(uri: Uri) = backupCoordinator.exportBackup(uri)
    fun exportProfile(profileId: Long, uri: Uri) = backupCoordinator.exportProfile(profileId, uri)
    fun confirmBackupImport() = backupCoordinator.confirmBackupImport()
    fun cancelBackupImport() = backupCoordinator.cancelBackupImport()
    fun importFile(uri: Uri) = backupCoordinator.importFile(uri)

    // Called while the coordinator holds the shared profile-session mutex.
    private suspend fun saveBeforeBackupExport(expected: ProfileSessionToken, profileId: Long?) {
        if ((profileId == null || profileId == activeProfileId) && hasUnsavedChanges()) {
            appProvider.saveActiveProfileIcons(expected)
            loadChangeBaselines(expected)
            refreshMissingPacks(prompt = false)
        }
    }

    private suspend fun onBackupRestored(resetMissingPackPrompt: Boolean) {
        refreshBackgroundSchedules()
        resetChangeBaselines()
        if (resetMissingPackPrompt) missingPacksPrompted.remove(appProvider.activeProfileId)
        refreshMissingPacks()
    }

    private suspend fun onBackupVerdictsUpdated(kind: BackupManager.ImportKind) {
        if (kind == BackupManager.ImportKind.BACKUP) {
            appProvider.reloadActiveProfile()
            resetChangeBaselines()
        }
        // A shared profile must not reload or discard edits in the active profile.
        refreshMissingPacks(prompt = false)
    }

    private suspend fun refreshBackgroundSchedules() {
        try {
            BackgroundWorkScheduler.reconcile(getApplication<Application>())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("MainViewModel", "Could not reschedule background work for restored settings", error)
        }
    }

    /** Of [prefixes], those that are genuine calendar day-rotation sets in [packPackageName]. */
    suspend fun calendarPrefixesAmong(packPackageName: String, prefixes: List<String>): Set<String> =
        appProvider.calendarPrefixesAmong(packPackageName, prefixes)

    /** Drawable names [packPackageName] declares as live-clock icons (see ApplicationProvider). */
    suspend fun dynamicClockDrawables(packPackageName: String): Set<String> =
        appProvider.dynamicClockDrawables(packPackageName)

}

private const val MAX_BASELINE_READ_ATTEMPTS = 3
