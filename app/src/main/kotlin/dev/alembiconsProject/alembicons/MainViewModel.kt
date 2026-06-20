package dev.alembiconsProject.alembicons

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.alembiconsProject.alembicons.apk.ApplicationProvider
import dev.alembiconsProject.alembicons.data.isSystemInDarkTheme
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
}
