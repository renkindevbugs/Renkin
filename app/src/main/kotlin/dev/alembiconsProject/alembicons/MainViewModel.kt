package dev.alembiconsProject.alembicons

import android.app.Application
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
