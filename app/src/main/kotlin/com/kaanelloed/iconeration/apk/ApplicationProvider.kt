package com.kaanelloed.iconeration.apk

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.kaanelloed.iconeration.R
import com.kaanelloed.iconeration.data.AlchemiconPackDatabase
import com.kaanelloed.iconeration.data.BackgroundColorKey
import com.kaanelloed.iconeration.data.CalendarIconsKey
import com.kaanelloed.iconeration.data.ColorizeIconPackKey
import com.kaanelloed.iconeration.data.DbApplication
import com.kaanelloed.iconeration.data.ExportThemedKey
import com.kaanelloed.iconeration.data.IconColorKey
import com.kaanelloed.iconeration.data.IconPack
import com.kaanelloed.iconeration.data.IconPackKey
import com.kaanelloed.iconeration.data.IncludeVectorKey
import com.kaanelloed.iconeration.data.InstalledApplication
import com.kaanelloed.iconeration.data.MonochromeKey
import com.kaanelloed.iconeration.data.OverrideIconKey
import com.kaanelloed.iconeration.data.RawElement
import com.kaanelloed.iconeration.data.TYPE_DEFAULT
import com.kaanelloed.iconeration.data.TypeKey
import com.kaanelloed.iconeration.data.getBooleanValue
import com.kaanelloed.iconeration.data.getColorValue
import com.kaanelloed.iconeration.data.getDefaultBackgroundColor
import com.kaanelloed.iconeration.data.getDefaultIconColor
import com.kaanelloed.iconeration.data.getEnumValue
import com.kaanelloed.iconeration.data.getStringValue
import com.kaanelloed.iconeration.drawable.ResourceDrawable
import com.kaanelloed.iconeration.extension.bitmapFromBase64
import com.kaanelloed.iconeration.icon.BitmapIcon
import com.kaanelloed.iconeration.icon.EmptyIcon
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.icon.creator.IconGenerator
import com.kaanelloed.iconeration.packages.ApplicationManager
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.ui.CreatedOptions
import com.kaanelloed.iconeration.ui.supportDynamicColors
import com.kaanelloed.iconeration.ui.toHexString
import com.kaanelloed.iconeration.ui.toInt
import com.kaanelloed.iconeration.vector.VectorParser
import com.kaanelloed.iconeration.xml.XmlDecoder

class ApplicationProvider(private val context: Context) {
    var applicationList: List<PackageInfoStruct> by mutableStateOf(listOf())
        private set
    var iconPacks: List<IconPack> = listOf()
        private set
    var iconPackLoaded: Boolean by mutableStateOf(false)
        private set

    private var iconPackAppFilterElement: Map<IconPack, List<RawElement>> = emptyMap()
    private var installedApplications: List<InstalledApplication> = listOf()
    private var calendarIcon: Map<InstalledApplication, String> = mapOf()
    private var calendarIconsDrawable: Map<String, Drawable> = emptyMap()

    var defaultColor: Color = Color.Unspecified

    suspend fun initialize() {
        initializeApplications()
        initializeIconPacks()
        initializeAlchemiconPack()
    }

    fun initializeApplications() {
        val apps = ApplicationManager(context).getAllInstalledApps()
        apps.sort()

        applicationList = apps.toList()
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun initializeIconPacks() {
        iconPacks = ApplicationManager(context).getIconPacks()
        getAppFilterElements()
    }

    suspend fun initializeAlchemiconPack() {
        loadAlchemiconPack()
    }

    fun retrieveOtherIcons(preferences: Preferences) {
        val iconPackageName = preferences.getStringValue(IconPackKey)
        val retrieveCalendarIcon = preferences.getBooleanValue(CalendarIconsKey)

        if (iconPackageName != "" && retrieveCalendarIcon) {
            retrieveCalendarIcons(iconPackageName)
        }
    }

    fun refreshIcon(application: PackageInfoStruct, preferences: Preferences) {
        val type = preferences.getEnumValue(TypeKey, TYPE_DEFAULT)
        val monochrome = preferences.getBooleanValue(MonochromeKey)
        val vector = preferences.getBooleanValue(IncludeVectorKey)
        val iconColorValue = preferences.getColorValue(IconColorKey
            , preferences.getDefaultIconColor(context))
        val bgColorValue = preferences.getColorValue(BackgroundColorKey
            , preferences.getDefaultBackgroundColor(context))
        val colorizeIconPack = preferences.getBooleanValue(ColorizeIconPackKey)
        val themed = preferences.getBooleanValue(ExportThemedKey)
        val iconPackageName = preferences.getStringValue(IconPackKey)

        val genOptions = IconGenerator.GenerationOptions(
            iconColorValue.toInt()
            , monochrome
            , vector
            , themed
            , bgColorValue.toInt()
            , colorizeIconPack)
        val options = CreatedOptions(genOptions, type, iconPackageName)

        refreshIcon(application, options)
    }

    fun refreshIcon(application: PackageInfoStruct, options: CreatedOptions) {
        val iconPackageName = options.iconPackageName

        if (iconPackageName != "" && iconPacks.any { it.packageName == iconPackageName }) {
            val iconPackApps = getIconPackAppDrawables(iconPackageName)
            val iconBuilder = getIconBuilder(options, iconPackApps, true)

            val packApp = iconPackApps.entries.find { it.key.packageName == application.packageName }

            if (packApp != null) {
                val icon = ApplicationManager(context).getResIcon(iconPackageName, packApp.value.resourceId)!!
                iconBuilder.updateFromIconPack(application, icon)
            } else {
                iconBuilder.generateIcons(application, options.generatingType)
            }
        } else {
            val iconBuilder = getIconBuilder(options, true)
            iconBuilder.generateIcons(application, options.generatingType)
        }
    }

    fun refreshIcons(preferences: Preferences) {
        val type = preferences.getEnumValue(TypeKey, TYPE_DEFAULT)
        val monochrome = preferences.getBooleanValue(MonochromeKey)
        val vector = preferences.getBooleanValue(IncludeVectorKey)
        val iconColorValue = preferences.getColorValue(IconColorKey
            , preferences.getDefaultIconColor(context))
        val bgColorValue = preferences.getColorValue(BackgroundColorKey
            , preferences.getDefaultBackgroundColor(context))
        val colorizeIconPack = preferences.getBooleanValue(ColorizeIconPackKey)
        val themed = preferences.getBooleanValue(ExportThemedKey)
        val dynamicColor = themed && supportDynamicColors()
        val iconPackageName = preferences.getStringValue(IconPackKey)
        val retrieveCalendarIcon = preferences.getBooleanValue(CalendarIconsKey)
        val overrideIcon = preferences.getBooleanValue(OverrideIconKey)

        val iconPackApps = getIconPackAppDrawables(iconPackageName)

        if (iconPackageName != "" && retrieveCalendarIcon) {
            retrieveCalendarIcons(iconPackageName)
        }

        var iconColor = iconColorValue.toInt()
        var bgColor = bgColorValue.toInt()

        if (dynamicColor) {
            iconColor = context.resources.getColor(R.color.icon_color, null)
            bgColor = context.resources.getColor(R.color.icon_background_color, null)
        }

        val opt = IconGenerator.GenerationOptions(iconColor, monochrome, vector, themed, bgColor, colorizeIconPack)
        val createdOptions = CreatedOptions(opt, type, iconPackageName)
        getIconBuilder(createdOptions, iconPackApps, overrideIcon).generateIcons(applicationList, type)
    }

    fun buildAndSignIconPack(preferences: Preferences, textMethod: (text: String) -> Unit): BuiltIconPack {
        val themed = preferences.getBooleanValue(ExportThemedKey)
        val iconColor = preferences.getDefaultIconColor(context)
        val bgColor = preferences.getDefaultBackgroundColor(context)

        val iconPackGenerator = IconPackBuilder(
            context,
            applicationList,
            calendarIcon,
            calendarIconsDrawable
        )
        val canBeInstalled = iconPackGenerator.canBeInstalled() // must be called before build and sign

        val apk = iconPackGenerator.buildAndSign(themed, iconColor.toHexString(), bgColor.toHexString(), textMethod)

        return BuiltIconPack(apk, iconPackGenerator.getIconPackName(), canBeInstalled)
    }

    suspend fun installIconPack(iconPack: BuiltIconPack): Boolean {
        var success = false

        if (iconPack.canBeInstalled) {
            success = ApkInstaller(context).install(iconPack.uri)
        } else {
            if (ApkUninstaller(context).uninstall(iconPack.packageName)) {
                success = ApkInstaller(context).install(iconPack.uri)
            }
        }

        saveAlchemiconPack()

        return success
    }

    private fun retrieveCalendarIcons(iconPackageName: String) {
        val appMan = ApplicationManager(context)

        val packApps = iconPackAppFilterElement.entries.find { it.key.packageName == iconPackageName }!!.value
        calendarIcon = appMan.getCalendarApplications(installedApplications, packApps)
        calendarIconsDrawable =
            appMan.getCalendarFromAppFilterElements(
                iconPackageName,
                packApps
            )
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun loadAlchemiconPack() {
        val db = Room.databaseBuilder(
            context,
            AlchemiconPackDatabase::class.java, "alchemiconPack"
        ).build()

        val dao = db.alchemiconPackDao()

        val dbApps = dao.getAll()
        val apps = applicationList.toList() //clone

        for (app in apps) {
            val dbApp = dbApps.find { it.packageName == app.packageName && it.activityName == app.activityName }
            if (dbApp != null) {
                val icon = if (dbApp.isXml) {
                    val nodes = XmlDecoder.fromBase64(dbApp.drawable)
                    val vector = VectorParser.parse(context.resources, nodes, defaultColor)

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

    private fun saveAlchemiconPack() {
        val db = Room.databaseBuilder(
            context,
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

    private fun getAppFilterElements() {
        val map = mutableMapOf<IconPack, List<RawElement>>()

        val appMan = ApplicationManager(context)
        installedApplications = appMan.getAllInstalledApplications()

        for (iconPack in iconPacks) {
            map[iconPack] = appMan.getAppFilterRawElements(iconPack.packageName, installedApplications)
        }

        iconPackAppFilterElement = map

        iconPackLoaded = true
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

    fun copy(): ApplicationProvider {
        val newProvider = ApplicationProvider(context)

        newProvider.applicationList = applicationList.toList()
        newProvider.iconPacks = iconPacks.toList()
        newProvider.iconPackLoaded = iconPackLoaded
        newProvider.iconPackAppFilterElement = iconPackAppFilterElement.toMap()
        newProvider.installedApplications = installedApplications.toList()
        newProvider.calendarIcon = calendarIcon.toMap()
        newProvider.calendarIconsDrawable = calendarIconsDrawable.toMap()
        newProvider.defaultColor = defaultColor

        return newProvider
    }

    private fun getIconPackAppDrawables(iconPack: String): Map<InstalledApplication, ResourceDrawable> {
        if (iconPack == "") return emptyMap()

        val apps = iconPackAppFilterElement.entries.find { it.key.packageName == iconPack }!!.value

        return ApplicationManager(context).getDrawableFromAppFilterElements(
            iconPack,
            installedApplications,
            apps
        )
    }

    fun getIconBuilder(options: CreatedOptions, override: Boolean): IconGenerator {
        val appDrawables = getIconPackAppDrawables(options.iconPackageName)
        return getIconBuilder(options, appDrawables, override)
    }

    private fun getIconBuilder(options: CreatedOptions
                               , applicationDrawables: Map<InstalledApplication, ResourceDrawable>
                               , override: Boolean): IconGenerator {
        return IconGenerator(context, this, options.generatingOptions, options.iconPackageName, applicationDrawables, override)
    }

    data class BuiltIconPack(
        val uri: Uri,
        val packageName: String,
        val canBeInstalled: Boolean
    )
}