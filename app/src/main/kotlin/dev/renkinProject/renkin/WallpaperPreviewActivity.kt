package dev.renkinProject.renkin

import android.content.pm.ActivityInfo
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dagger.hilt.android.AndroidEntryPoint
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.data.isDarkModeEnabled
import dev.renkinProject.renkin.ui.BuildPackPreviewContent
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
 * Reads shared state from the ApplicationProvider singleton directly — deliberately NOT through a
 * new MainViewModel, whose init would re-run the provider initialisation and drop unsaved icons.
 * The session-scoped built/updated key sets travel in via intent extras; RESULT_OK means the user
 * pressed Build, and the launching side starts the actual build.
 */
@AndroidEntryPoint
class WallpaperPreviewActivity : ComponentActivity() {
    @Inject
    lateinit var appProvider: ApplicationProvider

    private val toaster = Toaster()

    companion object {
        const val EXTRA_BUILT_KEYS = "builtKeys"
        const val EXTRA_UPDATED_KEYS = "updatedKeys"
        const val EXTRA_PROFILE_ID = "profileId"
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

        val builtKeys = intent.getStringArrayListExtra(EXTRA_BUILT_KEYS)?.toSet() ?: emptySet()
        val updatedKeys = intent.getStringArrayListExtra(EXTRA_UPDATED_KEYS)?.toSet() ?: emptySet()
        val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)

        setContent {
            val darkMode = applicationContext.dataStore.isDarkModeEnabled()
            val style = SystemBarStyle.auto(Color.Transparent.toArgb(), Color.Transparent.toArgb()) { darkMode }
            enableEdgeToEdge(style, style)

            // Own Toaster: the preview content (DisabledExplanation) reads LocalToaster, which is
            // otherwise only provided by MainActivity's composition.
            CompositionLocalProvider(LocalToaster provides toaster) {
                RenkinTheme(darkMode) {
                    LaunchedEffect(
                        appProvider.activeProfileId,
                        appProvider.isProfileSwitching
                    ) {
                        // The reviewed icons must stay tied to one profile. A watch deep link can
                        // otherwise switch the singleton provider underneath this activity.
                        if (appProvider.isProfileSwitching || appProvider.activeProfileId != profileId) {
                            finish()
                        }
                    }
                    BuildPackPreviewContent(
                        applications = appProvider.applicationList,
                        builtKeys = builtKeys,
                        updatedKeys = updatedKeys,
                        loadCalendarWarnings = { preferences -> appProvider.calendarWarnings(preferences) },
                        onDismiss = { finish() },
                        onBuild = {
                            setResult(
                                RESULT_OK,
                                Intent().putExtra(EXTRA_PROFILE_ID, profileId)
                            )
                            finish()
                        }
                    )
                    ToastHost(toaster)
                }
            }
        }
    }
}
