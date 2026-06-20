package dev.alembiconsProject.alembicons

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dev.alembiconsProject.alembicons.apk.ApplicationProvider
import dev.alembiconsProject.alembicons.data.PackageAddedNotificationKey
import dev.alembiconsProject.alembicons.data.getPreferenceFlow
import dev.alembiconsProject.alembicons.data.isDarkModeEnabled
import dev.alembiconsProject.alembicons.data.isSystemInDarkTheme
import dev.alembiconsProject.alembicons.data.setBooleanValue
import dev.alembiconsProject.alembicons.data.watch.WatchRepository
import dev.alembiconsProject.alembicons.packages.ApplicationManager
import dev.alembiconsProject.alembicons.packages.PermissionManager
import dev.alembiconsProject.alembicons.service.BootCompletedReceiver
import dev.alembiconsProject.alembicons.service.PackageAddedService
import dev.alembiconsProject.alembicons.service.WatchWorker
import dev.alembiconsProject.alembicons.ui.*
import dev.alembiconsProject.alembicons.ui.theme.IconerationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    val appProvider = ApplicationProvider(this)

    // Keys ("package/activity") of icons changed in this session, so the pack preview
    // can surface them first and flag them. Session-only (cleared on restart).
    // NOTE: experimental review aid — safe to drop with the commit that added it.
    val recentlyChangedIcons = androidx.compose.runtime.mutableStateListOf<String>()

    fun markIconChanged(packageName: String, activityName: String) {
        val key = "$packageName/$activityName"
        if (key !in recentlyChangedIcons) recentlyChangedIcons.add(key)
    }

    // Set when opened from an icon-watch notification; the home screen shows the apply
    // modal for this suggestion (consumed in the watch apply flow, phase 6).
    var pendingWatchSuggestionId by mutableStateOf<Long?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Landscape is only allowed on large screens (sw >= 600dp). Phones and
        // folded foldables stay locked to portrait.
        requestedOrientation = if (resources.getBoolean(R.bool.allowLandscape)) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        appProvider.defaultColor = if (this.isSystemInDarkTheme()) Color.White else Color.Black

        handleWatchIntent(intent)

        // App + pack loading runs in lifecycle-scoped coroutines (the heavy work
        // hops to Dispatchers.Default inside each call) so the main thread stays
        // free during startup; the UI shows loading state until the list arrives.
        // The Alchemicon pack reads applicationList, so it must run after the apps
        // are loaded; icon packs are independent and load in parallel.
        lifecycleScope.launch {
            appProvider.initializeApplications()
            appProvider.initializeAlchemiconPack()
        }
        lifecycleScope.launch { appProvider.initializeIconPacks() }

        // Icon-watch (phase 4): the daily safety-net check is always scheduled (version-gated,
        // so it's near-free when nothing changed); the event-driven fast path needs the
        // package receiver running, so start it when there are active watch rules.
        lifecycleScope.launch(Dispatchers.Default) {
            WatchWorker.schedulePeriodic(applicationContext)
            if (WatchRepository(applicationContext).getActiveRules().isNotEmpty()) {
                startPackageAddedService()
            }
        }

        lifecycleScope.launch(Dispatchers.Default) {
            applicationContext.dataStore.getPreferenceFlow(PackageAddedNotificationKey).collect {
                if (it == true) {
                    if (PermissionManager(this@MainActivity).isPostNotificationEnabled()) {
                        startPackageAddedService()
                    } else {
                        stopPackageAddedService()
                        applicationContext.dataStore.setBooleanValue(
                            PackageAddedNotificationKey, false)
                    }
                }
            }
        }

        setContent {
            val darkMode = applicationContext.dataStore.isDarkModeEnabled()
            edgeToEdge(darkMode)

            CompositionLocalProvider(LocalMainActivity provides this) {
                IconerationTheme(darkMode) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainColumn(appProvider.iconPacks)
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

    private fun handleWatchIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_SUGGESTION) {
            val id = intent.getLongExtra(EXTRA_SUGGESTION_ID, -1L)
            if (id >= 0) pendingWatchSuggestionId = id
        }
    }

    /** Called once the apply modal has handled (applied or dismissed) the suggestion. */
    fun clearPendingWatchSuggestion() {
        pendingWatchSuggestionId = null
    }

    private fun edgeToEdge(darkMode: Boolean) {
        val style = SystemBarStyle.auto(Color.Transparent.toArgb()
            , Color.Transparent.toArgb()
        ) { _ -> darkMode }

        enableEdgeToEdge(style, style)
    }

    fun startPackageAddedService() {
        togglePackageAddedService(true)
    }

    fun stopPackageAddedService() {
        togglePackageAddedService(false)
    }

    private fun togglePackageAddedService(enabled: Boolean) {
        val intent = Intent(this, PackageAddedService::class.java)

        if (enabled) {
            startService(intent)
        } else {
            stopService(intent)
        }

        ApplicationManager(this)
            .changeManifestEnabledState(BootCompletedReceiver::class.java, enabled)
    }

    companion object {
        const val ACTION_OPEN_SUGGESTION = "dev.alembiconsProject.alembicons.OPEN_SUGGESTION"
        const val EXTRA_SUGGESTION_ID = "watch_suggestion_id"
    }
}