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
import com.kaanelloed.iconeration.data.AlchemiconPackDatabase
import com.kaanelloed.iconeration.data.DbApplication
import com.kaanelloed.iconeration.data.IconPack
import com.kaanelloed.iconeration.data.InstalledApplication
import com.kaanelloed.iconeration.data.RawElement
import com.kaanelloed.iconeration.data.isDarkModeEnabled
import com.kaanelloed.iconeration.extension.bitmapFromBase64
import com.kaanelloed.iconeration.icon.BitmapIcon
import com.kaanelloed.iconeration.icon.EmptyIcon
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.packages.ApplicationManager
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.ui.*
import com.kaanelloed.iconeration.ui.theme.IconerationTheme
import com.kaanelloed.iconeration.vector.VectorParser
import com.kaanelloed.iconeration.xml.XmlDecoder
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
        loadAlchemiconPack()

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

    private fun loadAlchemiconPack() {
        CoroutineScope(Dispatchers.Default).launch {
            val db = Room.databaseBuilder(
                applicationContext,
                AlchemiconPackDatabase::class.java, "alchemiconPack"
            ).build()

            val dao = db.alchemiconPackDao()

            val dbApps = dao.getAll()
            val apps = applicationList.toList() //clone

            val appMan = ApplicationManager(applicationContext)

            for (app in apps) {
                val dbApp = dbApps.find { it.packageName == app.packageName && it.activityName == app.activityName }
                if (dbApp != null) {
                    val icon = if (dbApp.isXml) {
                        val nodes = XmlDecoder.fromBase64(dbApp.drawable)
                        val vector = VectorParser.parse(resources, nodes)

                        if (vector != null) {
                            VectorIcon(vector)
                        } else {
                            EmptyIcon()
                        }
                    } else {
                        BitmapIcon(bitmapFromBase64(dbApp.drawable), dbApp.isAdaptiveIcon)
                    }

                    editApplication(app, app.changeExport(icon))
                }
            }

            db.close()
        }
    }

    fun saveAlchemiconPack() {
        CoroutineScope(Dispatchers.Default).launch {
            val db = Room.databaseBuilder(
                applicationContext,
                AlchemiconPackDatabase::class.java, "alchemiconPack"
            ).build()

            val dbApps = mutableListOf<DbApplication>()

            for (app in applicationList) {
                if (app.createdIcon !is EmptyIcon) {
                    val isXml = app.createdIcon is VectorIcon

                    dbApps.add(
                        DbApplication(
                            app.packageName,
                            app.activityName,
                            app.createdIcon.exportAsAdaptiveIcon,
                            isXml,
                            app.createdIcon.toDbString()
                        )
                    )
                }
            }

            val packDao = db.alchemiconPackDao()

            packDao.deleteAllApplications()
            packDao.insertAll(dbApps)

            db.close()
        }
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