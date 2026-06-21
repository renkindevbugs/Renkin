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
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.alembiconsProject.alembicons.data.watch.WatchRepository
import dev.alembiconsProject.alembicons.packages.ApplicationManager
import dev.alembiconsProject.alembicons.service.BootCompletedReceiver
import dev.alembiconsProject.alembicons.service.PackageAddedService
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

    // The provider lives in the ViewModel so it (and its loaded state) survives
    // configuration changes; exposed here for the activity's own use in setContent.
    val appProvider get() = viewModel.appProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Landscape is only allowed on large screens (sw >= 600dp). Phones and
        // folded foldables stay locked to portrait.
        requestedOrientation = if (resources.getBoolean(R.bool.allowLandscape)) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        handleWatchIntent(intent)

        // Icon-watch: the daily safety-net check is always scheduled (version-gated,
        // so it's near-free when nothing changed); the event-driven fast path needs the
        // package receiver running, so start it when there are active watch rules.
        lifecycleScope.launch(Dispatchers.Default) {
            // KEEP so an already-running interval timer isn't reset on every launch; the
            // user's chosen interval is applied immediately (UPDATE) when they change it.
            val intervalMinutes = applicationContext.dataStore.data.first()
                .getIntValue(WatchCheckIntervalKey, WATCH_CHECK_INTERVAL_DEFAULT)
            WatchWorker.schedulePeriodic(applicationContext, intervalMinutes)
            if (WatchRepository(applicationContext).getActiveRules().isNotEmpty()) {
                startPackageAddedService()
            }
        }

        setContent {
            val darkMode = applicationContext.dataStore.isDarkModeEnabled()
            edgeToEdge(darkMode)

            val toaster = remember { Toaster() }
            val crashScope = rememberCoroutineScope()
            // Detected once per launch: if the previous session crashed, offer to upload the log.
            var crashPending by remember { mutableStateOf(CrashReporter.hasCrash(this@MainActivity)) }

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
                        MainColumn(appProvider.iconPacks)

                        if (crashPending) {
                            CrashReportDialog(
                                onSend = {
                                    crashPending = false
                                    crashScope.launch {
                                        val sent = CrashReporter.sendReport(this@MainActivity)
                                        toaster.show(
                                            getString(if (sent) R.string.crashSent else R.string.crashSendFailed)
                                        )
                                        // Keep the log on failure so the next launch can retry.
                                        if (sent) CrashReporter.clear(this@MainActivity)
                                    }
                                },
                                onDismiss = {
                                    CrashReporter.clear(this@MainActivity)
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
            if (!viewModel.appProvider.iconPackLoaded) return@launch
            val installed = ApplicationManager(this@MainActivity).getIconPacks()
            val loaded = viewModel.appProvider.iconPacks.map { it.packageName }.toSet()
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

    /** Starts the icon-watch package monitor and enables the boot receiver so it survives reboot. */
    fun startPackageAddedService() {
        startService(Intent(this, PackageAddedService::class.java))
        ApplicationManager(this)
            .changeManifestEnabledState(BootCompletedReceiver::class.java, true)
    }

    companion object {
        const val ACTION_OPEN_SUGGESTION = "dev.alembiconsProject.alembicons.OPEN_SUGGESTION"
        const val EXTRA_SUGGESTION_ID = "watch_suggestion_id"
    }
}