package dev.renkinProject.renkin

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.renkinProject.renkin.data.SharedIconHandoff
import dev.renkinProject.renkin.feature.presets.PresetLibraryViewModel
import dev.renkinProject.renkin.feature.presets.ProvideColorPresets
import dev.renkinProject.renkin.feature.presets.ProvideModifierPresets
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.ApplicationRuntimeState
import dev.renkinProject.renkin.apk.IconApplyOutcome
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.apk.ProfileOperationSupersededException
import dev.renkinProject.renkin.apk.ProfileOperationBusyException
import dev.renkinProject.renkin.apk.ProfileSessionChangedException
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.isDarkModeEnabled
import dev.renkinProject.renkin.data.dataStore
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconGenerationResult
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.ui.GlobalModifierSnapshot
import dev.renkinProject.renkin.ui.GlobalModifierState
import dev.renkinProject.renkin.ui.GlobalOptionsScreen
import dev.renkinProject.renkin.ui.LocalToaster
import dev.renkinProject.renkin.ui.ProvideAdaptiveLayoutInfo
import dev.renkinProject.renkin.ui.ToastHost
import dev.renkinProject.renkin.ui.Toaster
import dev.renkinProject.renkin.ui.theme.RenkinTheme
import dev.renkinProject.renkin.util.Log
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Hosts the Global options screen in its own activity for the same reason as
 * [WallpaperPreviewActivity]: its theme sets `windowShowWallpaper` + a transparent background,
 * so the real wallpaper composites behind the icon grid — the launcher trick, no permission
 * needed, and it doesn't work reliably for dialog windows.
 *
 * State lives in the shared [ApplicationProvider] singleton (via [GlobalOptionsViewModel] —
 * deliberately NOT a new MainViewModel, whose init would re-run provider initialisation and
 * drop unsaved icons). The session bookkeeping MainViewModel owns (updatedKeys, change
 * baselines) travels back through the activity result.
 */
@AndroidEntryPoint
class GlobalOptionsActivity : ComponentActivity() {

    private val toaster = Toaster()

    companion object {
        /** Keys hand-edited in the per-icon modifier dialog — MainViewModel marks them updated. */
        const val EXTRA_EDITED_KEYS = "editedKeys"

        /** True when Save baked+persisted — MainViewModel refreshes its change baselines. */
        const val EXTRA_GLOBAL_APPLIED = "globalApplied"
        const val EXTRA_SESSION_LOST = "sessionLost"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.showWallpaperBehindContent()

        // Same rule as MainActivity: landscape only on large screens.
        requestedOrientation = if (resources.getBoolean(R.bool.allowLandscape)) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        setContent {
            val darkMode = applicationContext.dataStore.isDarkModeEnabled()
            val style = SystemBarStyle.auto(Color.Transparent.toArgb(), Color.Transparent.toArgb()) { darkMode }
            enableEdgeToEdge(style, style)

            val viewModel: GlobalOptionsViewModel = hiltViewModel()
            val optionsViewModel: OptionsViewModel = hiltViewModel()
            val presetsViewModel: PresetLibraryViewModel = hiltViewModel()
            val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
            val profileChanged = viewModel.hasProfileChanged(runtimeState)
            val colorPresets by presetsViewModel.colorPresets.collectAsStateWithLifecycle()
            val modifierPresets by presetsViewModel.modifierPresets.collectAsStateWithLifecycle()

            // The screen is bound to the profile it opened with (see GlobalOptionsViewModel):
            // if that changes underneath, close and hand back what was already applied rather
            // than let a Save write one profile's staged recipe into another.
            LaunchedEffect(profileChanged) {
                if (profileChanged) {
                    setResult(
                        RESULT_OK,
                        Intent()
                            .withProfileSession(viewModel.screenSession)
                            .putExtra(EXTRA_SESSION_LOST, viewModel.sessionLost)
                            .putStringArrayListExtra(EXTRA_EDITED_KEYS, ArrayList(viewModel.editedKeys))
                            .putExtra(EXTRA_GLOBAL_APPLIED, viewModel.appliedGlobal)
                    )
                    finish()
                }
            }

            LaunchedEffect(Unit) {
                viewModel.toastEvents.collect { resId -> toaster.show(getString(resId)) }
            }
            LaunchedEffect(optionsViewModel) {
                optionsViewModel.toastEvents.collect { resId -> toaster.show(getString(resId)) }
            }
            LaunchedEffect(presetsViewModel) {
                presetsViewModel.toastEvents.collect { resId -> toaster.show(getString(resId)) }
            }

            ProvideAdaptiveLayoutInfo(this@GlobalOptionsActivity) {
                CompositionLocalProvider(LocalToaster provides toaster) {
                    ProvideColorPresets(
                        presets = colorPresets,
                        onSave = presetsViewModel::saveColorPreset,
                        onDelete = presetsViewModel::deleteColorPreset
                    ) {
                        ProvideModifierPresets(
                            presets = modifierPresets,
                            onSave = presetsViewModel::saveModifierPreset,
                            onUpdate = presetsViewModel::updateModifierPreset,
                            onRename = presetsViewModel::renameModifierPreset,
                            onMarkUsed = presetsViewModel::markModifierPresetUsed,
                            onDelete = presetsViewModel::deleteModifierPreset
                        ) {
                            RenkinTheme(darkMode) {
                                GlobalOptionsScreen(onClose = { editedKeys, applied ->
                                    setResult(
                                        RESULT_OK,
                                        Intent()
                                            .withProfileSession(viewModel.screenSession)
                                            .putExtra(EXTRA_SESSION_LOST, viewModel.sessionLost)
                                            .putStringArrayListExtra(EXTRA_EDITED_KEYS, ArrayList(editedKeys))
                                            .putExtra(EXTRA_GLOBAL_APPLIED, applied)
                                    )
                                    finish()
                                })
                                ToastHost(toaster)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Global options screen's model: it owns the staged draft, its saved baseline and the Save
 * run, and keeps the screen's session bookkeeping ([editedKeys], [appliedGlobal]) that the
 * activity reports back to MainViewModel. A cold process recreation initializes the shared
 * provider here because MainViewModel may not exist while this activity is on top.
 */

internal data class GlobalPreviewCacheKey(
    val component: String,
    val iconVersion: Int,
    val sourceOptions: GenerationOptions?,
    val modifierOptions: GenerationOptions?,
    val targetPx: Int
)

private const val MAX_PARALLEL_PREVIEWS = 4

internal fun previewParallelism(availableProcessors: Int): Int =
    availableProcessors.coerceIn(1, MAX_PARALLEL_PREVIEWS)

internal class GlobalPreviewBitmapCache(maxSizeBytes: Int = DEFAULT_PREVIEW_CACHE_BYTES) {
    private val cache = object : LruCache<GlobalPreviewCacheKey, Bitmap>(maxSizeBytes) {
        override fun sizeOf(key: GlobalPreviewCacheKey, value: Bitmap): Int = value.allocationByteCount
    }

    fun get(key: GlobalPreviewCacheKey): Bitmap? = cache.get(key)

    fun put(key: GlobalPreviewCacheKey, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    fun clear() = cache.evictAll()

    companion object {
        private const val DEFAULT_PREVIEW_CACHE_BYTES = 24 * 1024 * 1024
    }
}

@HiltViewModel
class GlobalOptionsViewModel @Inject constructor(
    application: Application,
    private val appProvider: ApplicationProvider,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application), IconPreviewBuilder {

    // One-shot toast events (string resource ids), collected by the activity. Same contract as
    // MainViewModel's: the message fires after the write, never optimistically from the UI.
    private val _toastEvents = kotlinx.coroutines.channels.Channel<Int>(
        kotlinx.coroutines.channels.Channel.BUFFERED
    )
    val toastEvents = _toastEvents.receiveAsFlow()

    val applicationState = appProvider.applicationState
    val installedIcons = appProvider.installedIcons
    val runtimeState = appProvider.runtimeState
    val iconPackState = appProvider.iconPackState

    /** A content URI an external editor can read [bitmap] from, or null when it cannot be written. */
    suspend fun shareIconForEditing(bitmap: Bitmap): Uri? = SharedIconHandoff.write(getApplication(), bitmap)

    private val previewCache = GlobalPreviewBitmapCache()
    private val previewLock = Any()
    private val previewPermits = Semaphore(previewParallelism(Runtime.getRuntime().availableProcessors()))
    private val inFlightPreviews = mutableMapOf<GlobalPreviewCacheKey, Deferred<Bitmap?>>()
    private var previewConfiguration: Pair<GenerationOptions?, GenerationOptions?>? = null
    private val _previewJobs = MutableStateFlow(0)
    val previewJobs = _previewJobs.asStateFlow()

    var initialLoadRunning by mutableStateOf(!appProvider.startupComplete)
        private set

    /**
     * The profile this screen belongs to — null until the provider is ready, because a cold
     * recreation starts on the default id before the real one is loaded.
     */
    var screenSession by mutableStateOf(
        savedStateHandle.profileSession() ?: if (appProvider.startupComplete) appProvider.sessionToken else null
    )
        private set

    /**
     * True when the active profile moved out from under this screen. Reaching it takes an
     * external intent starting a second MainActivity on top (a share, say), switching there and
     * coming back — rare, but the staged settings on screen belong to the profile it opened
     * with, and saving them into a different one silently rewrites that profile's recipe.
     */
    val profileChanged: Boolean
        get() = hasProfileChanged(runtimeState.value)

    fun hasProfileChanged(runtime: ApplicationRuntimeState): Boolean {
        val opened = screenSession ?: return false
        return runtime.ready && opened != runtime.sessionToken
    }

    val sessionLost: Boolean
        get() = screenSession?.providerInstanceId?.let {
            it != appProvider.sessionToken.providerInstanceId
        } == true

    private val expiredSessionNotice: Int
        get() = screenSession?.expiredSessionMessage(appProvider.sessionToken)
            ?: R.string.profileSessionChanged

    init {
        screenSession?.let { savedStateHandle.saveProfileSession(it) }
        if (initialLoadRunning) {
            viewModelScope.launch {
                try {
                    appProvider.ensureInitialized()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.error("GlobalOptionsViewModel", "Cold initialization failed", e)
                } finally {
                    initialLoadRunning = false
                    if (screenSession == null && appProvider.startupComplete) {
                        screenSession = appProvider.sessionToken
                        savedStateHandle.saveProfileSession(appProvider.sessionToken)
                    }
                }
            }
        }
    }

    /** Keys hand-edited via the per-icon dialog in this screen session. */
    val editedKeys = mutableSetOf<String>()

    /** True once a Save baked and persisted the global layer. */
    var appliedGlobal = false
        private set

    /** (done, total) while the global layer is re-rendered from icon bases; null when idle. */
    var globalApplyProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    // The staged settings and the last saved snapshot live here, not in the screen, so a Save
    // that outlives an activity recreation still updates the baseline the screen compares with.
    @OptIn(SavedStateHandleSaveableApi::class)
    internal val draft: GlobalModifierState by savedStateHandle.saveable(saver = GlobalModifierState.Saver) {
        GlobalModifierState()
    }

    @OptIn(SavedStateHandleSaveableApi::class)
    private val savedBaseline by savedStateHandle.saveable(saver = GlobalModifierState.BaselineSaver) {
        mutableStateOf<GlobalModifierSnapshot?>(null)
    }

    /** The snapshot the dirty check compares against; null until seeding completes. */
    internal var baseline: GlobalModifierSnapshot?
        get() = savedBaseline.value
        private set(value) { savedBaseline.value = value }

    internal val isDirty: Boolean get() = baseline.let { it != null && it != draft.snapshot() }

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            if (!draft.initialized) {
                draft.seedFrom(getApplication<Application>().dataStore.getPreferencesAfterPendingWrites())
                baseline = draft.snapshot()
            }
        }
    }

    fun resetDraft() {
        baseline?.let(draft::restore)
    }

    /** Saves the staged draft; the outcome arrives as a toast and as the updated [baseline]. */
    fun saveGlobalStyle(): Job? {
        if (saveJob?.isActive == true || globalApplyProgress != null || !draft.initialized) return null
        if (profileChanged) {
            _toastEvents.trySend(expiredSessionNotice)
            return null
        }
        val expected = screenSession ?: return null
        globalApplyProgress = 0 to 0
        return viewModelScope.launch {
            try {
                val savedState = draft.snapshot()
                // Read the captured draft after suspension, never the live controls.
                val stagedDraft = GlobalModifierState().apply { restore(savedState) }
                val preferences = getApplication<Application>().dataStore.getPreferencesAfterPendingWrites()
                appProvider.applyGlobalModifiers(
                    expected, stagedDraft.overlay(preferences), stagedDraft.toModifierOptions(),
                    savedState.applyGenerated, savedState.applyExisting,
                    savedState.applyCustom, savedState.includeEmpty
                ) { done, total -> globalApplyProgress = done to total }
                val committed = appProvider.commitSessionState(expected) {
                    appliedGlobal = true
                    baseline = savedState
                }
                _toastEvents.trySend(if (committed) R.string.globalOptionsApplied else expiredSessionNotice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProfileOperationBusyException) {
                _toastEvents.trySend(R.string.profileRenderBusy)
            } catch (e: ProfileOperationSupersededException) {
                _toastEvents.trySend(R.string.profileOperationSuperseded)
            } catch (e: ProfileSessionChangedException) {
                _toastEvents.trySend(e.userMessage())
            } catch (e: Exception) {
                Log.error("GlobalOptionsViewModel", "Saving global style failed", e)
                _toastEvents.trySend(R.string.globalOptionsApplyFailed)
            } finally {
                globalApplyProgress = null
            }
        }.also { saveJob = it }
    }

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

    override suspend fun applyModifier(icon: IconPackDrawable, options: GenerationOptions): IconPackDrawable =
        appProvider.applyModifier(icon, options)

    fun updatePreviewConfiguration(
        sourceOptions: GenerationOptions?,
        modifierOptions: GenerationOptions?
    ) {
        val configuration = sourceOptions to modifierOptions
        val obsolete = synchronized(previewLock) {
            if (previewConfiguration == configuration) return
            previewConfiguration = configuration
            previewCache.clear()
            inFlightPreviews.values.toList().also { inFlightPreviews.clear() }
        }
        obsolete.forEach { it.cancel() }
    }

    fun cachedModifiedPreview(
        app: PackageInfoStruct,
        options: GenerationOptions,
        targetPx: Int
    ): Bitmap? = previewCache.get(previewKey(app, null, options, targetPx))

    suspend fun modifiedPreview(
        app: PackageInfoStruct,
        options: GenerationOptions,
        targetPx: Int
    ): Bitmap? {
        val base = app.baseIcon ?: app.createdIcon ?: return null
        return loadPreview(previewKey(app, null, options, targetPx)) {
            appProvider.applyModifier(base, options)
        }
    }

    fun cachedGeneratedPreview(
        app: PackageInfoStruct,
        sourceOptions: GenerationOptions,
        modifierOptions: GenerationOptions?,
        targetPx: Int
    ): Bitmap? = previewCache.get(previewKey(app, sourceOptions, modifierOptions, targetPx))

    suspend fun generatedPreview(
        app: PackageInfoStruct,
        sourceOptions: GenerationOptions,
        modifierOptions: GenerationOptions?,
        targetPx: Int
    ): Bitmap? = loadPreview(previewKey(app, sourceOptions, modifierOptions, targetPx)) {
        appProvider.previewEmptyIcon(app, sourceOptions, modifierOptions)
    }

    private fun previewKey(
        app: PackageInfoStruct,
        sourceOptions: GenerationOptions?,
        modifierOptions: GenerationOptions?,
        targetPx: Int
    ) = GlobalPreviewCacheKey(
        app.key, app.internalVersion, sourceOptions, modifierOptions, targetPx.coerceAtLeast(1)
    )

    private suspend fun loadPreview(
        key: GlobalPreviewCacheKey,
        load: suspend () -> IconPackDrawable?
    ): Bitmap? {
        previewCache.get(key)?.let { return it }
        val deferred = synchronized(previewLock) {
            previewCache.get(key)?.let { return it }
            inFlightPreviews[key] ?: viewModelScope.async(Dispatchers.Default) {
                var bitmap: Bitmap? = null
                try {
                    bitmap = previewPermits.withPermit {
                        load()?.toSafeBitmapOrNull(key.targetPx, key.targetPx)
                    }
                    bitmap?.let { previewCache.put(key, it) }
                    bitmap
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } finally {
                    synchronized(previewLock) { inFlightPreviews.remove(key) }
                    _previewJobs.update { (it - 1).coerceAtLeast(0) }
                }
            }.also {
                inFlightPreviews[key] = it
                _previewJobs.update { count -> count + 1 }
            }
        }
        return deferred.await()
    }

    /**
     * Stores a hand-tuned icon from the per-icon modifier dialog: the same origin gate and
     * reviewed-icon commit as MainViewModel.applyIcon, but the updated-keys bookkeeping stays local
     * and travels back via the activity result. Returns false when the pick is locked.
     */
    suspend fun applyEditedIcon(
        app: PackageInfoStruct,
        icon: IconPackDrawable,
        sourcePackName: String?
    ): Boolean {
        val expected = screenSession ?: return false
        return try {
            when (appProvider.applyPickedIcon(expected, app, icon, sourcePackName) { live, origin, rendered ->
                live.changeExport(
                    rendered, sourcePackName = origin, isRefreshMade = false,
                    isCustom = true, isLegacy = false, baseIcon = icon,
                    sourceUrl = live.sourceUrl
                )
            }) {
                IconApplyOutcome.APPLIED ->
                    appProvider.commitSessionState(expected) {
                        if (appProvider.applicationList.any { it.key == app.key && it.baseIcon === icon }) {
                            editedKeys += app.key
                        }
                    }.also { committed ->
                        if (!committed) _toastEvents.trySend(expiredSessionNotice)
                    }
                IconApplyOutcome.LOCKED -> {
                    _toastEvents.trySend(R.string.iconOriginLocked)
                    false
                }
                IconApplyOutcome.PROFILE_CHANGED -> {
                    _toastEvents.trySend(expiredSessionNotice)
                    false
                }
                IconApplyOutcome.TARGET_GONE -> {
                    _toastEvents.trySend(R.string.iconEditTargetGone)
                    false
                }
                IconApplyOutcome.FAILED -> {
                    _toastEvents.trySend(R.string.profileOperationFailed)
                    false
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("GlobalOptionsViewModel", "Editing an icon failed", error)
            _toastEvents.trySend(R.string.profileOperationFailed)
            false
        }
    }
}
