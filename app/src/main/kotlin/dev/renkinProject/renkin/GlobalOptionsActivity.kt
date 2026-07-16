package dev.renkinProject.renkin

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.isDarkModeEnabled
import dev.renkinProject.renkin.data.persistGlobalModifierPrefs
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.ui.GlobalOptionsScreen
import dev.renkinProject.renkin.ui.LocalToaster
import dev.renkinProject.renkin.ui.ToastHost
import dev.renkinProject.renkin.ui.Toaster
import dev.renkinProject.renkin.ui.theme.RenkinTheme
import dev.renkinProject.renkin.util.Log
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

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

            CompositionLocalProvider(LocalToaster provides toaster) {
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

/**
 * The Global options screen's model: a thin pass-through to the [ApplicationProvider]
 * singleton with the screen's session bookkeeping ([editedKeys], [appliedGlobal]) that the
 * activity reports back to MainViewModel. No initialisation side effects on purpose.
 */
@HiltViewModel
class GlobalOptionsViewModel @Inject constructor(
    application: Application,
    private val appProvider: ApplicationProvider
) : AndroidViewModel(application), IconPreviewBuilder {

    val applicationList: List<PackageInfoStruct> get() = appProvider.applicationList
    val iconPacks: List<IconPack> get() = appProvider.iconPacks
    val lockedIconKeys: Set<String> get() = appProvider.lockedIconKeys

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

    /**
     * Re-renders the global layer without mutating persisted bases (see the provider).
     * [preferences] already contains the screen's staged values. Returns success; the
     * screen shows the outcome toast.
     */
    suspend fun applyGlobalModifiers(
        preferences: Preferences,
        modifierOptions: GenerationOptions,
        applyGenerated: Boolean,
        applyExisting: Boolean,
        applyCustom: Boolean,
        includeEmpty: Boolean
    ): Boolean {
        if (globalApplyProgress != null) return false
        globalApplyProgress = 0 to 0
        return try {
            appProvider.applyGlobalModifiers(
                preferences, modifierOptions,
                applyGenerated, applyExisting, applyCustom, includeEmpty
            ) { done, total -> globalApplyProgress = done to total }
            getApplication<Application>().dataStore.persistGlobalModifierPrefs(preferences)
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
                baseIcon = icon
            )
        )
        editedKeys += app.key
        return true
    }
}
