package dev.renkinProject.renkin

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
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
import dev.renkinProject.renkin.data.BuiltPrimaryIconPackKey
import dev.renkinProject.renkin.data.BuiltPrimarySourceKey
import dev.renkinProject.renkin.data.PrimaryIconPackKey
import dev.renkinProject.renkin.data.PrimarySourceKey
import dev.renkinProject.renkin.data.SOURCE_DEFAULT
import dev.renkinProject.renkin.data.UploadedImageStore
import dev.renkinProject.renkin.data.isDarkModeEnabled
import dev.renkinProject.renkin.data.WatchCheckIntervalKey
import dev.renkinProject.renkin.data.WATCH_CHECK_INTERVAL_DEFAULT
import dev.renkinProject.renkin.data.getEnumValue
import dev.renkinProject.renkin.data.getIntValue
import dev.renkinProject.renkin.data.getStringValue
import dev.renkinProject.renkin.data.setEnumValue
import dev.renkinProject.renkin.data.setStringValue
import dev.renkinProject.renkin.apk.IconPackBuilder
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.service.WatchWorker
import dev.renkinProject.renkin.util.CrashReporter
import dev.renkinProject.renkin.ui.*
import dev.renkinProject.renkin.ui.theme.RenkinTheme
import kotlinx.coroutines.Dispatchers
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    // Activity-scoped (not remember-ed in composition) so non-compose code — e.g. the share
    // receiver in handleSharedImage — can queue toasts through the same single ToastHost.
    private val toaster = Toaster()

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
        handleSharedImage(intent)

        // Icon-watch: schedule the periodic safety-net check (version-gated, so it's near-free
        // when nothing changed). This is the only watch trigger — see WatchWorker.
        lifecycleScope.launch(Dispatchers.Default) {
            // A hero-card pack pick only sticks once it's built: restore the last-built primary
            // source/pack over any unbuilt pick from the previous session. Missing built keys =
            // legacy or fresh install — leave the current selection alone.
            val startupPrefs = applicationContext.dataStore.data.first()
            if (startupPrefs.contains(BuiltPrimarySourceKey)) {
                applicationContext.dataStore.setEnumValue(
                    PrimarySourceKey, startupPrefs.getEnumValue(BuiltPrimarySourceKey, SOURCE_DEFAULT)
                )
                applicationContext.dataStore.setStringValue(
                    PrimaryIconPackKey, startupPrefs.getStringValue(BuiltPrimaryIconPackKey)
                )
            }

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
            // Pack resources must resolve values-night the way the UI displays, not the way
            // the system is set — mode-dependent pack colours are invisible otherwise.
            ApplicationManager.displayedNightMode = darkMode

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
        handleSharedImage(intent)
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
                !IconPackBuilder.isOwnPack(it.packageName) && it.packageName !in loaded
            } ?: return@launch
            // A brand-new pack may already carry icons for watched apps — run the watch check
            // now instead of waiting for the periodic worker. Cheap: version-gating makes the
            // run a no-op for every pack that didn't change, and duplicate runs upsert the
            // same state without firing twice.
            WatchWorker.runNow(applicationContext)
            withContext(Dispatchers.Main) {
                viewModel.onIconPackInstalled(newPack.packageName, newPack.applicationName)
            }
        }
    }

    private fun handleWatchIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_SUGGESTION) {
            val id = intent.getLongExtra(EXTRA_SUGGESTION_ID, -1L)
            // Switches to the owning profile first (auto-saving the current one), then opens.
            if (id >= 0) viewModel.openSuggestionInProfile(id)
        }
    }

    /**
     * An image shared back from an external editor (the SEND intent-filter): decode it and store it
     * in the upload gallery, so the user can pick it as an icon from the edit dialog's Upload tab.
     */
    private fun handleSharedImage(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("image/") != true) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = getBitmapFromURI(applicationContext, uri) ?: return@launch
            UploadedImageStore.save(applicationContext, bitmap)
            toaster.show(getString(R.string.sharedImageAdded))
        }
    }

    private fun edgeToEdge(darkMode: Boolean) {
        val style = SystemBarStyle.auto(Color.Transparent.toArgb()
            , Color.Transparent.toArgb()
        ) { _ -> darkMode }

        enableEdgeToEdge(style, style)
    }


    companion object {
        const val ACTION_OPEN_SUGGESTION = "dev.renkinProject.renkin.OPEN_SUGGESTION"
        const val EXTRA_SUGGESTION_ID = "watch_suggestion_id"
    }
}
