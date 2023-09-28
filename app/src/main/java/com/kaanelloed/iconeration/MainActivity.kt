package com.kaanelloed.iconeration

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.core.graphics.drawable.toBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.kaanelloed.iconeration.data.DarkMode
import com.kaanelloed.iconeration.data.getDarkMode
import com.kaanelloed.iconeration.data.getDarkModeValue
import com.kaanelloed.iconeration.ui.*
import com.kaanelloed.iconeration.ui.theme.IconerationTheme

class MainActivity : ComponentActivity() {
    val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
    /*val db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "packapps"
        ).build()*/

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apps = ApplicationManager(applicationContext).getInstalledApps()
        for (app in apps) {
            app.genIcon = app.icon.toBitmap()
        }

        apps.sort()

        /*val packDao = db.iconPackDao()
        val packApps = packDao.getIconPacksWithApps()*/

        setContent {
            val darkMode = when (applicationContext.dataStore.getDarkModeValue()) {
                DarkMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
                DarkMode.DARK -> true
                DarkMode.LIGHT -> false
            }

            IconerationTheme(darkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column {
                        TitleBar(applicationContext.dataStore)
                        OptionsCard()
                        CreateButton(applicationContext, apps)
                        ApplicationList(apps)
                    }
                }
            }
        }
    }
}