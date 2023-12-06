package com.kaanelloed.iconeration

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
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
import com.kaanelloed.iconeration.data.IconPackApplication
import com.kaanelloed.iconeration.data.isDarkModeEnabled
import com.kaanelloed.iconeration.ui.*
import com.kaanelloed.iconeration.ui.theme.IconerationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    var applicationList: List<PackageInfoStruct> by mutableStateOf(listOf())
        private set

    var iconPackApplications: Map<IconPack, List<IconPackApplication>> = emptyMap()
        private set

    var iconPackLoaded: Boolean = false
        private set

    init {
        instance = this
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apps = ApplicationManager(applicationContext).getAllInstalledApps()
        apps.sort()

        val iconPacks = ApplicationManager(applicationContext).getIconPacks()
        syncIconPacks()

        applicationList = apps.toList()

        setContent {
            val darkMode = applicationContext.dataStore.isDarkModeEnabled()

            IconerationTheme(darkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column {
                        TitleBar()
                        OptionsCard(iconPacks)
                        ApplicationList(iconPacks)
                    }
                }
            }
        }
    }

    private fun syncIconPacks() {
        CoroutineScope(Dispatchers.Default).launch {
            val appMan = ApplicationManager(applicationContext)
            val iconPacks = appMan.getIconPacks()

            val db = Room.databaseBuilder(
                applicationContext,
                AppDatabase::class.java, "iconPackApps"
            ).build()

            val packDao = db.iconPackDao()
            val packList = packDao.getAll()

            packDao.deleteInstalledApplications()
            packDao.insertAll(appMan.getAllInstalledApplications())

            //Remove uninstalled icon packs
            for (dbApp in packList) {
                if (!iconPacks.any { it.packageName == dbApp.packageName }) {
                    packDao.delete(dbApp)
                    packDao.deleteApplicationByIconPackage(dbApp.packageName)
                }
            }

            for (iconPack in iconPacks) {
                val dbApp = packList.find { it.packageName == iconPack.packageName }
                val sameVersion = if (dbApp != null) { dbApp.versionCode == iconPack.versionCode } else false

                if (!sameVersion) {
                    if (dbApp != null) {
                        packDao.delete(dbApp)
                        packDao.deleteApplicationByIconPackage(iconPack.packageName)
                    }

                    val packApps = appMan.getIconPackApplications(iconPack.packageName)
                    packDao.insertIconPackWithApplications(iconPack, packApps)
                }
            }

            iconPackApplications = packDao.getIconPacksWithInstalledApps()
            iconPackLoaded = true
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

    companion object {
        private var instance: MainActivity? = null

        fun baseContext(): Context? {
            return instance?.baseContext
        }

        fun current(): MainActivity? {
            return instance
        }
    }
}