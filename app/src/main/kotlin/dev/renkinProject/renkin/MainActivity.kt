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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import dev.renkinProject.renkin.feature.presets.PresetLibraryViewModel
import dev.renkinProject.renkin.feature.backup.BackupRecoveryScreen
import dev.renkinProject.renkin.feature.presets.ProvideColorPresets
import dev.renkinProject.renkin.feature.presets.ProvideModifierPresets
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.StorageRecoveryState
import dev.renkinProject.renkin.data.isDarkModeEnabled
import dev.renkinProject.renkin.apk.IconPackBuilder
import dev.renkinProject.renkin.apk.ExternalInstallAwaiter
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.IconPackCatalog
import dev.renkinProject.renkin.service.WatchWorker
import dev.renkinProject.renkin.util.CrashReporter
import dev.renkinProject.renkin.ui.*
import dev.renkinProject.renkin.ui.theme.RenkinTheme
import kotlinx.coroutines.Dispatchers
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var iconPackCatalog: IconPackCatalog

    private val viewModel: MainViewModel by viewModels()
    private val presetsViewModel: PresetLibraryViewModel by viewModels()
    private val editorViewModel: EditorSessionViewModel by viewModels()
    private val editorDraftViewModel: EditorDraftRecoveryViewModel by viewModels()
    private var consumedSharedImageUri: String? = null

    // Activity-scoped (not remember-ed in composition) so non-compose code — e.g. the share
    // receiver in handleSharedImage — can queue toasts through the same single ToastHost.
    private val toaster = Toaster()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumedSharedImageUri = savedInstanceState?.getString(STATE_CONSUMED_SHARED_IMAGE_URI)

        // Start loading before handling launch intents or composing the startup shell.
        viewModel
        editorDraftViewModel.connect(editorViewModel)

        // Landscape is only allowed on large screens (sw >= 600dp). Phones and
        // folded foldables stay locked to portrait.
        requestedOrientation = if (resources.getBoolean(R.bool.allowLandscape)) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        handleWatchIntent(intent)
        handleSharedImage(intent)

        lifecycleScope.launch(Dispatchers.Default) {
            // Drop crash logs older than the retention window (and migrate any legacy log).
            CrashReporter.prune(applicationContext)
        }

        setContent {
            val iconPackState by viewModel.iconPackState.collectAsStateWithLifecycle()
            val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
            val recoveryState by StorageOperationGate.recoveryState.collectAsStateWithLifecycle()
            val recoveryFailureReason by StorageOperationGate.recoveryFailureReason.collectAsStateWithLifecycle()
            if (recoveryState == StorageRecoveryState.RECOVERING || recoveryState == StorageRecoveryState.FAILED ||
                (recoveryState == StorageRecoveryState.CHECKING && viewModel.storageRecoveryRetryRunning)) {
                RenkinTheme {
                    BackupRecoveryScreen(
                        state = recoveryState,
                        failureReason = recoveryFailureReason,
                        actionError = viewModel.recoveryActionError,
                        busy = viewModel.storageRecoveryRetryRunning,
                        onRetry = viewModel::retryStorageRecovery,
                        onKeepCurrent = viewModel::abandonStorageRecovery
                    )
                }
                return@setContent
            }
            val darkMode = applicationContext.dataStore.isDarkModeEnabled()
            val startupLoading = recoveryState == StorageRecoveryState.CHECKING ||
                (!runtimeState.startupComplete && !viewModel.startupFailed)
            edgeToEdge(darkMode)
            // Pack resources must resolve values-night the way the UI displays, not the way
            // the system is set — mode-dependent pack colours are invisible otherwise.
            SideEffect { ApplicationManager.displayedNightMode = darkMode }

            // Detected once per launch: if the previous session crashed, offer the log for
            // manual reporting (copy / email / GitHub) — nothing is sent automatically.
            var crashPending by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                crashPending = withContext(Dispatchers.IO) { CrashReporter.hasNewCrash(this@MainActivity) }
            }

            val colorPresets by presetsViewModel.colorPresets.collectAsStateWithLifecycle()
            val modifierPresets by presetsViewModel.modifierPresets.collectAsStateWithLifecycle()
            LaunchedEffect(presetsViewModel) {
                presetsViewModel.toastEvents.collect { resId -> toaster.show(getString(resId)) }
            }

            ProvideAdaptiveLayoutInfo(this@MainActivity) {
                CompositionLocalProvider(
                    LocalMainActivity provides this,
                    LocalToaster provides toaster
                ) {
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
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.background
                                ) {
                                    ToastHost(toaster)
                                    if (startupLoading) MainStartupScreen() else MainColumn(iconPackState.packs)

                                    if (crashPending && !startupLoading) {
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
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        consumedSharedImageUri?.let { outState.putString(STATE_CONSUMED_SHARED_IMAGE_URI, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A newly delivered SEND is a new user action even when it points to the same URI.
        consumedSharedImageUri = null
        handleWatchIntent(intent)
        handleSharedImage(intent)
    }

    override fun onStart() {
        super.onStart()
        editorDraftViewModel.setBackgrounded(false)
        // An icon pack installed while the app was away won't be in the loaded list yet.
        // On every return to the foreground, diff the installed icon packs against what we
        // have loaded; a new one (other than our own generated pack) prompts a reload so it
        // shows up among the sources. Covers installs done while Renkin was backgrounded
        // (the installer obscures us, so this fires when the user comes back).
        lifecycleScope.launch(Dispatchers.Default) {
            // Skip until the initial pack load finished, otherwise every installed pack
            // looks "new" against the still-empty loaded list on first launch.
            if (!viewModel.iconPackState.value.loaded) return@launch
            val installed = iconPackCatalog.installedIconPacks()
            val loaded = viewModel.iconPackState.value.packs.map { it.packageName }.toSet()
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

    override fun onStop() {
        if (!isChangingConfigurations) {
            editorDraftViewModel.setBackgrounded(true)
            viewModel.flushUnsavedJournal()
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Third-party installers such as InstallerX use a translucent/dialog Activity. Renkin
        // stays STARTED underneath it, so cancellation returns through onResume(), not onStart().
        ExternalInstallAwaiter.onRenkinForegrounded()
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
        // Activity state survives both configuration changes and process restoration; mutating an
        // incoming Intent extra does not reliably survive the latter.
        val uriKey = uri.toString()
        if (consumedSharedImageUri == uriKey) return
        consumedSharedImageUri = uriKey

        lifecycleScope.launch {
            // Same import as the gallery: a revoked, one-shot or broken provider URI only fails.
            val added = viewModel.uploadedImages.import(listOf(uri)).added.isNotEmpty()
            toaster.show(getString(if (added) R.string.sharedImageAdded else R.string.uploadImageError))
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

private const val STATE_CONSUMED_SHARED_IMAGE_URI = "consumedSharedImageUri"
