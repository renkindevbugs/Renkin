package com.kaanelloed.iconeration

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.kaanelloed.iconeration.apk.ApplicationProvider
import com.kaanelloed.iconeration.data.PackageAddedNotificationKey
import com.kaanelloed.iconeration.data.getPreferenceFlow
import com.kaanelloed.iconeration.data.isDarkModeEnabled
import com.kaanelloed.iconeration.data.isSystemInDarkTheme
import com.kaanelloed.iconeration.data.setBooleanValue
import com.kaanelloed.iconeration.packages.ApplicationManager
import com.kaanelloed.iconeration.packages.PermissionManager
import com.kaanelloed.iconeration.service.BootCompletedReceiver
import com.kaanelloed.iconeration.service.PackageAddedService
import com.kaanelloed.iconeration.ui.*
import com.kaanelloed.iconeration.ui.theme.IconerationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    val appProvider = ApplicationProvider(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appProvider.defaultColor = if (this.isSystemInDarkTheme()) Color.White else Color.Black

        appProvider.initializeApplications()
        CoroutineScope(Dispatchers.Default).launch {
            appProvider.initializeIconPacks()
        }
        CoroutineScope(Dispatchers.Default).launch {
            appProvider.initializeAlchemiconPack()
        }

        CoroutineScope(Dispatchers.Default).launch {
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
}