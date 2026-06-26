package dev.alembiconsProject.alembicons

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dev.alembiconsProject.alembicons.data.isDarkModeEnabled
import dev.alembiconsProject.alembicons.data.WatchCheckIntervalKey
import dev.alembiconsProject.alembicons.data.WATCH_CHECK_INTERVAL_DEFAULT
import dev.alembiconsProject.alembicons.data.getIntValue
import dev.alembiconsProject.alembicons.apk.IconPackBuilder
import dev.alembiconsProject.alembicons.packages.ApplicationManager
import dev.alembiconsProject.alembicons.service.WatchWorker
import dev.alembiconsProject.alembicons.util.CrashReporter
import dev.alembiconsProject.alembicons.ui.*
import dev.alembiconsProject.alembicons.ui.theme.RenkinTheme
import kotlinx.coroutines.Dispatchers
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Instantiate the view model now, on the main thread, before setContent. This forces
        // its ApplicationProvider — and the Compose snapshot state that provider holds
        // (iconPacks, applicationsLoaded, …) — to be created in the global snapshot. If the
        // first touch instead happened while reading viewModel.iconPacks during the initial
        // composition, Compose throws "Reading a state that was created after the snapshot
        // was taken or in a snapshot that has not yet been applied".
        viewModel

        // Landscape is only allowed on large screens (sw >= 600dp). Phones and
        // folded foldables stay locked to portrait.
        requestedOrientation = if (resources.getBoolean(R.bool.allowLandscape)) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        handleWatchIntent(intent)

        // Icon-watch: schedule the periodic safety-net check (version-gated, so it's near-free
        // when nothing changed). This is the only watch trigger — see WatchWorker.
        lifecycleScope.launch(Dispatchers.Default) {
            // KEEP so an already-running interval timer isn't reset on every launch; the
            // user's chosen interval is applied immediately (UPDATE) when they change it.
            val intervalMinutes = applicationContext.dataStore.data.first()
                .getIntValue(WatchCheckIntervalKey, WATCH_CHECK_INTERVAL_DEFAULT)
            WatchWorker.schedulePeriodic(applicationContext, intervalMinutes)
            // Drop crash logs older than the retention window (and migrate any legacy log).
            CrashReporter.prune(applicationContext)
        }

        setContent {
            val darkMode = applicationContext.dataStore.isDarkModeEnabled()
            edgeToEdge(darkMode)

            val toaster = remember { Toaster() }
            // Detected once per launch: if the previous session crashed, offer the log for
            // manual reporting (copy / email / GitHub) — nothing is sent automatically.
            var crashPending by remember { mutableStateOf(CrashReporter.hasNewCrash(this@MainActivity)) }

            CompositionLocalProvider(
                LocalMainActivity provides this,
                LocalToaster provides toaster
            ) {
                RenkinTheme(darkMode) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        ToastHost(toaster)
                        MainColumn(viewModel.iconPacks)

                        if (crashPending) {
                            CrashReportDialog(
                                onDismiss = {
                                    CrashReporter.markCrashesSeen(this@MainActivity)
                                    crashPending = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWatchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // An icon pack installed while the app was away won't be in the loaded list yet.
        // On every return to the foreground, diff the installed icon packs against what we
        // have loaded; a new one (other than our own generated pack) prompts a reload so it
        // shows up among the sources. Covers installs done while Renkin was backgrounded
        // (the installer obscures us, so this fires when the user comes back).
        lifecycleScope.launch(Dispatchers.Default) {
            // Skip until the initial pack load finished, otherwise every installed pack
            // looks "new" against the still-empty loaded list on first launch.
            if (!viewModel.iconPackLoaded) return@launch
            val installed = ApplicationManager(this@MainActivity).getIconPacks()
            val loaded = viewModel.iconPacks.map { it.packageName }.toSet()
            val newPack = installed.firstOrNull {
                it.packageName != IconPackBuilder.PACKAGE_NAME && it.packageName !in loaded
            } ?: return@launch
            withContext(Dispatchers.Main) {
                viewModel.onIconPackInstalled(newPack.packageName, newPack.applicationName)
            }
        }
    }

    private fun handleWatchIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_SUGGESTION) {
            val id = intent.getLongExtra(EXTRA_SUGGESTION_ID, -1L)
            if (id >= 0) viewModel.setPendingWatchSuggestion(id)
        }
    }

    private fun edgeToEdge(darkMode: Boolean) {
        val style = SystemBarStyle.auto(Color.Transparent.toArgb()
            , Color.Transparent.toArgb()
        ) { _ -> darkMode }

        enableEdgeToEdge(style, style)
    }


    companion object {
        const val ACTION_OPEN_SUGGESTION = "dev.alembiconsProject.alembicons.OPEN_SUGGESTION"
        const val EXTRA_SUGGESTION_ID = "watch_suggestion_id"
    }
}