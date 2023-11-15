package com.kaanelloed.iconeration

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.graphics.drawable.toBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.kaanelloed.iconeration.data.AppDatabase
import com.kaanelloed.iconeration.data.isDarkModeEnabled
import com.kaanelloed.iconeration.ui.*
import com.kaanelloed.iconeration.ui.theme.IconerationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apps = ApplicationManager(applicationContext).getAllInstalledApps()
        for (app in apps) {
            app.genIcon = app.icon.toBitmap()
        }

        apps.sort()

        val iconPacks = ApplicationManager(applicationContext).getIconPacks()
        syncIconPacks()

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
                        Row(modifier = Modifier.fillMaxWidth()) {
                            RefreshButton(apps)
                            BuildPackButton(apps)
                        }
                        ApplicationList(iconPacks, apps)
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
        }
    }
}