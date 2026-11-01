package dev.renkinProject.renkin

import android.content.pm.ActivityInfo
import android.content.Intent
import android.os.Bundle
import dev.renkinProject.renkin.feature.language.LocalizedActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.feature.buildinstall.PackPreviewEditorOwners
import dev.renkinProject.renkin.feature.buildinstall.LocalIconEditorOwner
import dev.renkinProject.renkin.feature.presets.PresetLibraryViewModel
import dev.renkinProject.renkin.feature.presets.ProvideModifierPresets
import dev.renkinProject.renkin.feature.presets.ProvideColorPresets
import dev.renkinProject.renkin.ui.ProvideAdaptiveLayoutInfo
import dev.renkinProject.renkin.ui.OpenAppOptions
import dev.renkinProject.renkin.ui.CollectToasts
import dev.renkinProject.renkin.data.ExportThemedKey
import dev.renkinProject.renkin.data.getBooleanValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dagger.hilt.android.AndroidEntryPoint
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.isDarkModeEnabled
import dev.renkinProject.renkin.data.dataStore
import dev.renkinProject.renkin.feature.buildinstall.BuildPackPreviewContent
import dev.renkinProject.renkin.ui.LocalToaster
import dev.renkinProject.renkin.ui.ToastHost
import dev.renkinProject.renkin.ui.Toaster
import dev.renkinProject.renkin.ui.theme.RenkinTheme
import javax.inject.Inject

/**
 * Fullscreen pack preview drawn over the user's real wallpaper. Its theme sets
 * `windowShowWallpaper` + a transparent background, so the system composites the actual (even
 * live) wallpaper behind the window — the launcher trick; the wallpaper bitmap is never read, so
 * no permission is needed (WallpaperManager.getDrawable is locked behind MANAGE_EXTERNAL_STORAGE
 * since Android 13). This must be an activity: WindowManager doesn't reliably make dialog windows
 * the wallpaper target.
 *
 * Reads the provider's live state and borrows the existing home ViewModel for per-icon edits.
 * A new MainViewModel would re-run initialization and drop unsaved icons. The opening built/updated
 * keys travel in intent extras; RESULT_OK asks the home owner to build the reviewed live state.
 */
@AndroidEntryPoint
class WallpaperPreviewActivity : LocalizedActivity() {
    @Inject
    lateinit var appProvider: ApplicationProvider

    private val toaster = Toaster()

    companion object {
        const val EXTRA_BUILT_KEYS = "builtKeys"
        const val EXTRA_UPDATED_KEYS = "updatedKeys"
        const val EXTRA_SESSION_CHANGED = "sessionChanged"
        const val EXTRA_EDITOR_OWNER = "editorOwner"

        /** Set on a CANCELED result when the process died under the preview (see onCreate). */
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

        // Android can recreate this activity alone on top of a killed process (savedInstanceState
        // present, provider empty). The reviewed session is gone then — initializing the provider
        // here would quietly show the last SAVED state instead, which is not what the user was
        // reviewing. Decide before composing so no empty grid flashes and no calendar-warning
        // load starts; MainActivity comes back and cold-loads normally.
        if (savedInstanceState != null && !appProvider.startupComplete) {
            setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_SESSION_LOST, true))
            finish()
            return
        }

        val builtKeys = intent.getStringArrayListExtra(EXTRA_BUILT_KEYS)?.toSet() ?: emptySet()
        val updatedKeys = intent.getStringArrayListExtra(EXTRA_UPDATED_KEYS)?.toSet() ?: emptySet()
        val expected = intent.profileSession()
        if (expected == null) {
            finish()
            return
        }
        if (!appProvider.isCurrentSession(expected)) {
            cancelExpiredPreview(expected)
            return
        }
        val editorOwner = PackPreviewEditorOwners.find(intent.getStringExtra(EXTRA_EDITOR_OWNER))
        if (editorOwner == null) {
            setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_SESSION_LOST, true))
            finish()
            return
        }

        setContent {
            val applicationState by appProvider.applicationState.collectAsStateWithLifecycle()
            val runtimeState by appProvider.runtimeState.collectAsStateWithLifecycle()
            val iconPacks by editorOwner.iconPackState.collectAsStateWithLifecycle()
            val library: PresetLibraryViewModel = hiltViewModel()
            val options: OptionsViewModel = hiltViewModel()
            val preferences by options.preferenceSnapshot.collectAsStateWithLifecycle()
            val colorPresets by library.colorPresets.collectAsStateWithLifecycle()
            val modifierPresets by library.modifierPresets.collectAsStateWithLifecycle()
            val shortcutUsage by library.modifierShortcutUsage.collectAsStateWithLifecycle()
            var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
            val darkMode = applicationContext.dataStore.isDarkModeEnabled()
            val style = SystemBarStyle.auto(Color.Transparent.toArgb(), Color.Transparent.toArgb()) { darkMode }
            enableEdgeToEdge(style, style)

            // Own Toaster: the preview content (DisabledExplanation) reads LocalToaster, which is
            // otherwise only provided by MainActivity's composition.
            ProvideAdaptiveLayoutInfo(this@WallpaperPreviewActivity) {
                CompositionLocalProvider(LocalToaster provides toaster, LocalIconEditorOwner provides editorOwner) {
                    ProvideColorPresets(colorPresets, library::saveColorPreset, library::deleteColorPreset) {
                        ProvideModifierPresets(
                            presets = modifierPresets,
                            onSave = library::saveModifierPreset,
                            onUpdate = library::updateModifierPreset,
                            onRename = library::renameModifierPreset,
                            onMarkUsed = library::markModifierPresetUsed,
                            onDelete = library::deleteModifierPreset,
                            shortcutUsage = shortcutUsage,
                            onDismissShortcuts = library::dismissModifierShortcuts
                        ) {
                            RenkinTheme(darkMode) {
                                CollectToasts(toaster, editorOwner.toastEvents, options.toastEvents, library.toastEvents)
                                LaunchedEffect(
                                    runtimeState.sessionToken,
                                    runtimeState.isProfileSwitching
                                ) {
                                    // The reviewed icons must stay tied to one profile. A watch deep link can
                                    // otherwise switch the singleton provider underneath this activity.
                                    if (!appProvider.isCurrentSession(expected)) {
                                        cancelExpiredPreview(expected)
                                    }
                                }
                                BuildPackPreviewContent(
                                    applications = applicationState.applications,
                                    builtKeys = builtKeys,
                                    updatedKeys = updatedKeys + editorOwner.updatedKeys,
                                    loadCalendarWarnings = { preferences -> appProvider.calendarWarnings(preferences) },
                                    onEditIcon = { editingKey = it.key },
                                    onDismiss = { finish() },
                                    onBuild = {
                                        if (appProvider.isCurrentSession(expected)) {
                                            setResult(RESULT_OK, Intent().withProfileSession(expected))
                                            finish()
                                        } else {
                                            cancelExpiredPreview(expected)
                                        }
                                    }
                                )
                                editingKey?.let { key ->
                                    val app = applicationState.applications.firstOrNull { it.key == key }
                                    if (app != null) OpenAppOptions(
                                        iconPacks = iconPacks.packs,
                                        app = app,
                                        themed = preferences.getBooleanValue(ExportThemedKey),
                                        sourceSession = expected,
                                        awaitApply = true,
                                        onDismiss = { editingKey = null }
                                    ) else LaunchedEffect(key) { editingKey = null }
                                }
                                ToastHost(toaster)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) PackPreviewEditorOwners.release(intent.getStringExtra(EXTRA_EDITOR_OWNER))
        super.onDestroy()
    }

    private fun cancelExpiredPreview(expected: ProfileSessionToken) {
        val lostProcess = expected.providerInstanceId != appProvider.sessionToken.providerInstanceId
        setResult(
            RESULT_CANCELED,
            Intent().withProfileSession(expected)
                .putExtra(if (lostProcess) EXTRA_SESSION_LOST else EXTRA_SESSION_CHANGED, true)
        )
        finish()
    }
}
