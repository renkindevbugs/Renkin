package dev.renkinProject.renkin

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.stateIn
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.isDarkModeEnabled
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.ui.GlobalOptionsScreen
import dev.renkinProject.renkin.ui.LocalToaster
import dev.renkinProject.renkin.ui.ProvideColorPresets
import dev.renkinProject.renkin.ui.ToastHost
import dev.renkinProject.renkin.ui.Toaster
import dev.renkinProject.renkin.ui.theme.RenkinTheme
import dev.renkinProject.renkin.util.Log
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
            val colorPresets by viewModel.colorPresets.collectAsState()

            // The screen is bound to the profile it opened with (see GlobalOptionsViewModel):
            // if that changes underneath, close and hand back what was already applied rather
            // than let a Save write one profile's staged recipe into another.
            LaunchedEffect(viewModel.profileChanged) {
                if (viewModel.profileChanged) {
                    setResult(
                        RESULT_OK,
                        Intent()
                            .putStringArrayListExtra(EXTRA_EDITED_KEYS, ArrayList(viewModel.editedKeys))
                            .putExtra(EXTRA_GLOBAL_APPLIED, viewModel.appliedGlobal)
                    )
                    finish()
                }
            }

            CompositionLocalProvider(LocalToaster provides toaster) {
              ProvideColorPresets(
                presets = colorPresets,
                onSave = viewModel::saveColorPreset,
                onDelete = viewModel::deleteColorPreset
              ) {
                RenkinTheme(darkMode) {
                    GlobalOptionsScreen(onClose = { editedKeys, applied ->
                        setResult(
                            RESULT_OK,
                            Intent()
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

/**
 * The Global options screen's model: a thin pass-through to the [ApplicationProvider]
 * singleton with the screen's session bookkeeping ([editedKeys], [appliedGlobal]) that the
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
    private val appProvider: ApplicationProvider
) : AndroidViewModel(application), IconPreviewBuilder {

    /** Saved colours/gradients, the same library the main screen's sheets use. */
    val colorPresets: kotlinx.coroutines.flow.StateFlow<List<dev.renkinProject.renkin.data.ColorPreset>> =
        appProvider.colorPresets().stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            emptyList()
        )

    fun saveColorPreset(name: String, style: String) {
        viewModelScope.launch { appProvider.saveColorPreset(name, style) }
    }

    fun deleteColorPreset(id: Long) {
        viewModelScope.launch { appProvider.deleteColorPreset(id) }
    }

    val applicationList: List<PackageInfoStruct> get() = appProvider.applicationList
    val iconPacks: List<IconPack> get() = appProvider.iconPacks
    val lockedIconKeys: Set<String> get() = appProvider.lockedIconKeys

    private val previewCache = GlobalPreviewBitmapCache()
    private val previewLock = Any()
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
    var screenProfileId by mutableStateOf(if (appProvider.startupComplete) appProvider.activeProfileId else null)
        private set

    /**
     * True when the active profile moved out from under this screen. Reaching it takes an
     * external intent starting a second MainActivity on top (a share, say), switching there and
     * coming back — rare, but the staged settings on screen belong to the profile it opened
     * with, and saving them into a different one silently rewrites that profile's recipe.
     */
    val profileChanged: Boolean
        get() {
            val opened = screenProfileId ?: return false
            return !appProvider.isProfileSwitching && appProvider.activeProfileId != opened
        }

    init {
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
                    screenProfileId = appProvider.activeProfileId
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

    override suspend fun previewIcon(
        app: PackageInfoStruct,
        options: GenerationOptions,
        customIcon: ResourceDrawable?
    ): IconPackDrawable? = appProvider.getIcon(app, options, customIcon)

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
        val base = appProvider.getIcon(app, sourceOptions, null)
        if (base != null && modifierOptions != null) {
            appProvider.applyModifier(base, modifierOptions)
        } else base
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
                    bitmap = load()?.toSafeBitmapOrNull(key.targetPx, key.targetPx)
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
     * Re-renders the global layer without mutating persisted bases (see the provider).
     * [preferences] contains the screen's staged values. The provider persists that recipe and
     * the rendered icons as one profile operation. Returns success for the screen's outcome toast.
     */
    suspend fun applyGlobalModifiers(
        preferences: Preferences,
        modifierOptions: GenerationOptions,
        applyGenerated: Boolean,
        applyExisting: Boolean,
        applyCustom: Boolean,
        includeEmpty: Boolean
    ): Boolean {
        // Staged values belong to the profile this screen opened with; the activity closes on a
        // change, but a Save already in flight when it happens must not land in the new profile.
        if (globalApplyProgress != null || profileChanged) return false
        globalApplyProgress = 0 to 0
        return try {
            appProvider.applyGlobalModifiers(
                preferences, modifierOptions,
                applyGenerated, applyExisting, applyCustom, includeEmpty
            ) { done, total -> globalApplyProgress = done to total }
            appliedGlobal = true
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.error("GlobalOptionsViewModel", "Applying global modifiers failed", e)
            false
        } finally {
            globalApplyProgress = null
        }
    }

    /**
     * Stores a hand-tuned icon from the per-icon modifier dialog: the same origin gate and
     * custom-render as MainViewModel.applyIcon, but the updated-keys bookkeeping stays local
     * and travels back via the activity result. Returns false when the pick is locked.
     */
    suspend fun applyEditedIcon(
        app: PackageInfoStruct,
        icon: IconPackDrawable,
        sourcePackName: String?
    ): Boolean {
        val (origin, locked) = appProvider.resolvePickedSource(app, sourcePackName)
        if (locked) return false
        val preferences = getApplication<Application>().dataStore.getPreferencesAfterPendingWrites()
        val rendered = appProvider.renderCustomIcon(icon, preferences)
        val index = appProvider.applicationList.indexOfFirst { it.key == app.key }
        if (index < 0) return true
        appProvider.editApplication(
            index,
            appProvider.applicationList[index].changeExport(
                rendered,
                sourcePackName = origin,
                isRefreshMade = false,
                isCustom = true,
                isLegacy = false,
                baseIcon = icon,
                // A modifier tweak reworks the same artwork — its attribution stays.
                sourceUrl = app.sourceUrl
            )
        )
        editedKeys += app.key
        return true
    }
}
