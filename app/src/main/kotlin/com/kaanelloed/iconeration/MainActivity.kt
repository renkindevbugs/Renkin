package com.kaanelloed.iconeration

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.kaanelloed.iconeration.data.AppDatabase
import com.kaanelloed.iconeration.data.IconPack
import com.kaanelloed.iconeration.data.InstalledApplication
import com.kaanelloed.iconeration.data.RawElement
import com.kaanelloed.iconeration.data.isDarkModeEnabled
import com.kaanelloed.iconeration.packages.ApplicationManager
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.ui.*
import com.kaanelloed.iconeration.ui.theme.IconerationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    var applicationList: List<PackageInfoStruct> by mutableStateOf(listOf())
        private set

    var iconPackAppFilterElement: Map<IconPack, List<RawElement>> = emptyMap()
        private set

    var iconPacks: List<IconPack> = listOf()
        private set

    var installedApplications: List<InstalledApplication> = listOf()
        private set

    var calendarIcon: Map<InstalledApplication, String> = mapOf()

    var calendarIconsDrawable: Map<String, Drawable> = emptyMap()

    var iconPackLoaded: Boolean by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apps = ApplicationManager(applicationContext).getAllInstalledApps()
        apps.sort()

        iconPacks = ApplicationManager(applicationContext).getIconPacks()

        getAppFilterElements()

        applicationList = apps.toList()

        setContent {
            val darkMode = applicationContext.dataStore.isDarkModeEnabled()

            IconerationTheme(darkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainColumn(iconPacks)
                }
            }
        }
    }

    private fun getAppFilterElements() {
        CoroutineScope(Dispatchers.Default).launch {
            val map = mutableMapOf<IconPack, List<RawElement>>()

            val appMan = ApplicationManager(applicationContext)
            installedApplications = appMan.getAllInstalledApplications()

            for (iconPack in iconPacks) {
                map[iconPack] = appMan.getAppFilterRawElements(iconPack.packageName, installedApplications)
            }

            iconPackAppFilterElement = map

            iconPackLoaded = true
        }
    }

    private fun saveAlchemiconPack() {
        val db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "iconPackApps"
        ).build()

        val packDao = db.iconPackDao()
    }

    fun forceSync() {
        if (iconPackLoaded) {
            iconPackLoaded = false
            getAppFilterElements()
        }
    }

    fun editApplication(oldApp: PackageInfoStruct, newApp: PackageInfoStruct) {
        val index = applicationList.indexOf(oldApp)
        if (index >= 0)
            editApplication(index, newApp)
    }

    fun editApplication(index: Int, newApp: PackageInfoStruct) {
        applicationList = applicationList.toMutableList().also {
            it[index] = newApp
        }
    }
}