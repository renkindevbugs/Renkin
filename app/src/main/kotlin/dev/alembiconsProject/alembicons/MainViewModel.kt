package dev.alembiconsProject.alembicons

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.alembiconsProject.alembicons.apk.ApplicationProvider
import dev.alembiconsProject.alembicons.data.PrimaryIconPackKey
import dev.alembiconsProject.alembicons.data.getStringValue
import dev.alembiconsProject.alembicons.data.isSystemInDarkTheme
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the [ApplicationProvider] for the app's lifetime. The provider is injected (a Hilt
 * @Singleton), so the loaded app list / icon packs survive configuration changes such as
 * rotation instead of being re-loaded on every Activity recreation.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    val appProvider: ApplicationProvider
) : AndroidViewModel(application) {

    // Keys ("package/activity") of the apps already in the last built/saved pack.
    // An app with an icon whose key is NOT here is "added" (pending build); a key here
    // whose app no longer has an icon is "removed". Reloaded after each successful build,
    // so the change state is a diff against what was actually built (survives refresh).
    var builtKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    // Set when opened from an icon-watch notification; the home screen shows the apply
    // modal for this suggestion.
    var pendingWatchSuggestionId by mutableStateOf<Long?>(null)
        private set

    fun setPendingWatchSuggestion(id: Long) { pendingWatchSuggestionId = id }

    fun clearPendingWatchSuggestion() { pendingWatchSuggestionId = null }

    // One-shot toast events (string resource ids). Emitted when an operation finishes and
    // collected once near the composition root, which forwards them to the shared Toaster.
    // Replaces the old mirrored buildInstalled/syncDone/appsRefreshed flag + consume pairs.
    private val _toastEvents = Channel<Int>(Channel.BUFFERED)
    val toastEvents = _toastEvents.receiveAsFlow()

    init {
        appProvider.defaultColor =
            if (application.isSystemInDarkTheme()) Color.White else Color.Black

        // Loaded once. The Renkin pack reads the app list, so it runs after the
        // apps are loaded; icon packs are independent and load in parallel. The heavy
        // work hops to Dispatchers.Default inside each call, so viewModelScope (main)
        // is fine here.
        viewModelScope.launch {
            appProvider.initializeApplications()
            appProvider.initializeRenkinPack()
            builtKeys = appProvider.getSavedPackKeys()
        }
        viewModelScope.launch { appProvider.initializeIconPacks() }
    }

    // ---- Operation orchestration -------------------------------------------------
    // The heavy work lives in suspend functions on ApplicationProvider (each hops to
    // Dispatchers.Default internally), so these run safely on viewModelScope (main).
    // UI state is exposed as Compose state; composables read it and call these instead
    // of spinning up their own lifecycleScope coroutines.

    /** True while [refresh] is regenerating icons. Drives the refresh spinner and blocks build. */
    var isRefreshing by mutableStateOf(false)
        private set

    /**
     * Regenerates every app's icon from the current preferences. Returns false (without
     * starting) when an icon pack is configured but not yet loaded, so the caller can warn.
     */
    fun refresh(preferences: Preferences): Boolean {
        val iconPackageName = preferences.getStringValue(PrimaryIconPackKey)
        if (!appProvider.iconPackLoaded && iconPackageName != "") return false
        if (isRefreshing) return true

        viewModelScope.launch {
            isRefreshing = true
            appProvider.refreshIcons(preferences)
            isRefreshing = false
        }
        return true
    }

    /** Current build step text while a pack is building; null when no build is in progress. */
    var buildStep by mutableStateOf<String?>(null)
        private set

    /** Builds, signs and installs the icon pack, surfacing progress through [buildStep]. */
    fun build(preferences: Preferences) {
        if (buildStep != null) return
        viewModelScope.launch {
            buildStep = ""
            val pack = appProvider.buildAndSignIconPack(preferences) { buildStep = it }
            buildStep = null
            if (appProvider.installIconPack(pack)) {
                _toastEvents.trySend(R.string.iconPackInstalled)
                // The saved pack now matches the current icons → reset the change baseline.
                builtKeys = appProvider.getSavedPackKeys()
            }
        }
    }

    /** Assigns (or clears, when [icon] is null) the created icon for the app at [index]. */
    fun applyIcon(index: Int, app: PackageInfoStruct, icon: IconPackDrawable?) {
        appProvider.editApplication(index, app.changeExport(icon))
    }

    /** Re-reads the installed icon packs. */
    fun sync() {
        viewModelScope.launch {
            appProvider.forceSync()
            _toastEvents.trySend(R.string.packsSynced)
        }
    }

    /** Reloads apps, icon packs and the saved Alchemicon pack from scratch. */
    fun refreshApps() {
        viewModelScope.launch {
            appProvider.initialize()
            _toastEvents.trySend(R.string.appListRefreshed)
        }
    }

    /** Clears every created icon (and persists the empty state). */
    fun clearIcons() {
        viewModelScope.launch {
            appProvider.clearIcons()
            // Saved pack is now empty → reset the change baseline so the bar clears too.
            builtKeys = appProvider.getSavedPackKeys()
        }
    }
}
