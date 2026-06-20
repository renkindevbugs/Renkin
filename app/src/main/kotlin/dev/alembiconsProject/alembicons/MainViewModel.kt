package dev.alembiconsProject.alembicons

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.alembiconsProject.alembicons.apk.ApplicationProvider
import dev.alembiconsProject.alembicons.data.PrimaryIconPackKey
import dev.alembiconsProject.alembicons.data.getStringValue
import dev.alembiconsProject.alembicons.data.isSystemInDarkTheme
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import kotlinx.coroutines.launch

/**
 * Owns the [ApplicationProvider] for the app's lifetime. Because it lives in a
 * ViewModel (built with the application context, not an Activity), the loaded app
 * list / icon packs survive configuration changes such as rotation instead of
 * being re-loaded on every Activity recreation.
 *
 * First brick toward a proper ViewModel layer: the provider is still reached via
 * MainActivity.appProvider for now, so existing call sites are untouched.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val appProvider = ApplicationProvider(application)

    // Keys ("package/activity") of icons changed in this session, so the pack preview
    // can surface them first and flag them. Session-only (cleared on restart).
    val recentlyChangedIcons = mutableStateListOf<String>()

    fun markIconChanged(packageName: String, activityName: String) {
        val key = "$packageName/$activityName"
        if (key !in recentlyChangedIcons) recentlyChangedIcons.add(key)
    }

    // Set when opened from an icon-watch notification; the home screen shows the apply
    // modal for this suggestion.
    var pendingWatchSuggestionId by mutableStateOf<Long?>(null)
        private set

    fun setPendingWatchSuggestion(id: Long) { pendingWatchSuggestionId = id }

    fun clearPendingWatchSuggestion() { pendingWatchSuggestionId = null }

    init {
        appProvider.defaultColor =
            if (application.isSystemInDarkTheme()) Color.White else Color.Black

        // Loaded once. The Alchemicon pack reads the app list, so it runs after the
        // apps are loaded; icon packs are independent and load in parallel. The heavy
        // work hops to Dispatchers.Default inside each call, so viewModelScope (main)
        // is fine here.
        viewModelScope.launch {
            appProvider.initializeApplications()
            appProvider.initializeAlchemiconPack()
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

    /** One-shot: set true once a freshly built pack installs; consume with [consumeBuildInstalled]. */
    var buildInstalled by mutableStateOf(false)
        private set

    fun consumeBuildInstalled() { buildInstalled = false }

    /** Builds, signs and installs the icon pack, surfacing progress through [buildStep]. */
    fun build(preferences: Preferences) {
        if (buildStep != null) return
        viewModelScope.launch {
            buildStep = ""
            val pack = appProvider.buildAndSignIconPack(preferences) { buildStep = it }
            buildStep = null
            if (appProvider.installIconPack(pack)) buildInstalled = true
        }
    }

    /** Assigns (or clears, when [icon] is null) the created icon for the app at [index]. */
    fun applyIcon(index: Int, app: PackageInfoStruct, icon: IconPackDrawable?) {
        appProvider.editApplication(index, app.changeExport(icon))
        if (icon != null) markIconChanged(app.packageName, app.activityName)
    }

    /** One-shot signal that a settings operation finished; consume with [consumeSyncDone]. */
    var syncDone by mutableStateOf(false)
        private set

    fun consumeSyncDone() { syncDone = false }

    /** Re-reads the installed icon packs. */
    fun sync() {
        viewModelScope.launch {
            appProvider.forceSync()
            syncDone = true
        }
    }

    /** One-shot signal that the app list finished reloading; consume with [consumeAppsRefreshed]. */
    var appsRefreshed by mutableStateOf(false)
        private set

    fun consumeAppsRefreshed() { appsRefreshed = false }

    /** Reloads apps, icon packs and the saved Alchemicon pack from scratch. */
    fun refreshApps() {
        viewModelScope.launch {
            appProvider.initialize()
            appsRefreshed = true
        }
    }

    /** Clears every created icon. */
    fun clearIcons() {
        viewModelScope.launch { appProvider.clearIcons() }
    }
}
