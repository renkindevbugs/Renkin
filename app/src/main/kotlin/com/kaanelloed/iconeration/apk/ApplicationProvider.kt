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
import com.kaanelloed.iconeration.constants.SuppressRedundantSuspendModifier
import com.kaanelloed.iconeration.data.AlchemiconPackDatabase
import com.kaanelloed.iconeration.data.BackgroundColorKey
import com.kaanelloed.iconeration.data.CalendarIconsKey
import com.kaanelloed.iconeration.data.DbApplication
import com.kaanelloed.iconeration.data.ExportThemedKey
import com.kaanelloed.iconeration.data.IMAGE_EDIT_DEFAULT
import com.kaanelloed.iconeration.data.IconColorKey
import com.kaanelloed.iconeration.data.IconPack
import com.kaanelloed.iconeration.data.IncludeVectorKey
import com.kaanelloed.iconeration.data.InstalledApplication
import com.kaanelloed.iconeration.data.MonochromeKey
import com.kaanelloed.iconeration.data.OverrideIconKey
import com.kaanelloed.iconeration.data.PrimaryIconPackKey
import com.kaanelloed.iconeration.data.PrimaryImageEditKey
import com.kaanelloed.iconeration.data.PrimarySourceKey
import com.kaanelloed.iconeration.data.PrimaryTextTypeKey
import com.kaanelloed.iconeration.data.RawElement
import com.kaanelloed.iconeration.data.SOURCE_DEFAULT
import com.kaanelloed.iconeration.data.SecondaryIconPackKey
import com.kaanelloed.iconeration.data.SecondaryImageEditKey
import com.kaanelloed.iconeration.data.SecondarySourceKey
import com.kaanelloed.iconeration.data.SecondaryTextTypeKey
import com.kaanelloed.iconeration.data.TEXT_TYPE_DEFAULT
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
import com.kaanelloed.iconeration.icon.ExportableIcon
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.icon.creator.GenerationOptions
import com.kaanelloed.iconeration.icon.creator.IconGenerator
import com.kaanelloed.iconeration.icon.creator.IconPackContainer
import com.kaanelloed.iconeration.packages.ApplicationManager
import com.kaanelloed.iconeration.packages.PackageInfoStruct
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

    private var am: ApplicationManager? = null
    private val appManager: ApplicationManager
        get() {
            if (am == null) am = ApplicationManager(context)
            return am!!
        }

    suspend fun initialize() {
        initializeApplications()
        initializeIconPacks()
        initializeAlchemiconPack()
    }

    fun initializeApplications() {
        val apps = appManager.getAllInstalledApps()
        apps.sort()

        applicationList = apps.toList()
    }

    @Suppress(SuppressRedundantSuspendModifier)
    suspend fun initializeIconPacks() {
        iconPackLoaded = false
        iconPacks = appManager.getIconPacks()
        getAppFilterElements()
    }

    suspend fun initializeAlchemiconPack() {
        loadAlchemiconPack()
    }

    fun retrieveOtherIcons(preferences: Preferences) {
        val iconPackageName = preferences.getStringValue(PrimaryIconPackKey)
        val retrieveCalendarIcon = preferences.getBooleanValue(CalendarIconsKey)

        if (iconPackageName != "" && retrieveCalendarIcon) {
            retrieveCalendarIcons(iconPackageName)
        }
    }

    fun refreshIcon(application: PackageInfoStruct, preferences: Preferences) {
        val primarySource = preferences.getEnumValue(PrimarySourceKey, SOURCE_DEFAULT)
        val primaryImageEdit = preferences.getEnumValue(PrimaryImageEditKey, IMAGE_EDIT_DEFAULT)
        val primaryTextType = preferences.getEnumValue(PrimaryTextTypeKey, TEXT_TYPE_DEFAULT)
        val primaryIconPack = preferences.getStringValue(PrimaryIconPackKey)
        val secondarySource = preferences.getEnumValue(SecondarySourceKey, SOURCE_DEFAULT)
        val secondaryImageEdit = preferences.getEnumValue(SecondaryImageEditKey, IMAGE_EDIT_DEFAULT)
        val secondaryTextType = preferences.getEnumValue(SecondaryTextTypeKey, TEXT_TYPE_DEFAULT)
        val secondaryIconPack = preferences.getStringValue(SecondaryIconPackKey)
        val monochrome = preferences.getBooleanValue(MonochromeKey)
        val vector = preferences.getBooleanValue(IncludeVectorKey)
        val iconColorValue = preferences.getColorValue(IconColorKey
            , preferences.getDefaultIconColor(context))
        val bgColorValue = preferences.getColorValue(BackgroundColorKey
            , preferences.getDefaultBackgroundColor(context))
        val themed = preferences.getBooleanValue(ExportThemedKey)

        val genOptions = GenerationOptions(
            primarySource
            , primaryImageEdit
            , primaryTextType
            , primaryIconPack
            , secondarySource
            , secondaryImageEdit
            , secondaryTextType
            , secondaryIconPack
            , iconColorValue.toInt()
            , bgColorValue.toInt()
            , vector
            , monochrome
            , themed
            , true)

        refreshIcon(application, genOptions)
    }

    private fun refreshIcon(application: PackageInfoStruct, options: GenerationOptions) {
        val primaryIconPackApps = getIconPackAppDrawables(options.primaryIconPack)
        val secondaryIconPackApps = getIconPackAppDrawables(options.secondaryIconPack)

        val pack1 = IconPackContainer(options.primaryIconPack, primaryIconPackApps)
        val pack2 = IconPackContainer(options.secondaryIconPack, secondaryIconPackApps)

        val builder = IconGenerator(context, options, pack1, pack2)
        builder.generateIcon(application) { app, icon ->
            editApplication(app, app.changeExport(icon))
        }
    }

    fun refreshIcons(preferences: Preferences) {
        val primarySource = preferences.getEnumValue(PrimarySourceKey, SOURCE_DEFAULT)
        val primaryImageEdit = preferences.getEnumValue(PrimaryImageEditKey, IMAGE_EDIT_DEFAULT)
        val primaryTextType = preferences.getEnumValue(PrimaryTextTypeKey, TEXT_TYPE_DEFAULT)
        val primaryIconPack = preferences.getStringValue(PrimaryIconPackKey)
        val secondarySource = preferences.getEnumValue(SecondarySourceKey, SOURCE_DEFAULT)
        val secondaryImageEdit = preferences.getEnumValue(SecondaryImageEditKey, IMAGE_EDIT_DEFAULT)
        val secondaryTextType = preferences.getEnumValue(SecondaryTextTypeKey, TEXT_TYPE_DEFAULT)
        val secondaryIconPack = preferences.getStringValue(SecondaryIconPackKey)
        val monochrome = preferences.getBooleanValue(MonochromeKey)
        val vector = preferences.getBooleanValue(IncludeVectorKey)
        val iconColorValue = preferences.getColorValue(IconColorKey
            , preferences.getDefaultIconColor(context))
        val bgColorValue = preferences.getColorValue(BackgroundColorKey
            , preferences.getDefaultBackgroundColor(context))
        val themed = preferences.getBooleanValue(ExportThemedKey)
        val dynamicColor = themed && supportDynamicColors()
        val retrieveCalendarIcon = preferences.getBooleanValue(CalendarIconsKey)
        val overrideIcon = preferences.getBooleanValue(OverrideIconKey)

        val primaryIconPackApps = getIconPackAppDrawables(primaryIconPack)
        val secondaryIconPackApps = getIconPackAppDrawables(secondaryIconPack)

        if (primaryIconPack != "" && retrieveCalendarIcon) {
            retrieveCalendarIcons(primaryIconPack)
        }

        var iconColor = iconColorValue.toInt()
        var bgColor = bgColorValue.toInt()

        if (dynamicColor) {
            iconColor = context.resources.getColor(R.color.icon_color, null)
            bgColor = context.resources.getColor(R.color.icon_background_color, null)
        }

        val opt = GenerationOptions(
            primarySource,
            primaryImageEdit,
            primaryTextType,
            primaryIconPack,
            secondarySource,
            secondaryImageEdit,
            secondaryTextType,
            secondaryIconPack,
            iconColor,
            bgColor,
            vector,
            monochrome,
            themed,
            overrideIcon
        )

        val pack1 = IconPackContainer(primaryIconPack, primaryIconPackApps)
        val pack2 = IconPackContainer(secondaryIconPack, secondaryIconPackApps)

        val builder = IconGenerator(context, opt, pack1, pack2)
        builder.generateIcons(applicationList) { application, icon ->
            editApplication(application, application.changeExport(icon))
        }
    }

    fun getIcon(application: PackageInfoStruct, options: GenerationOptions): ExportableIcon {
        var icon: ExportableIcon = EmptyIcon()

        val primaryIconPackApps = getIconPackAppDrawables(options.primaryIconPack)

        val pack1 = IconPackContainer(options.primaryIconPack, primaryIconPackApps)
        val pack2 = IconPackContainer("", emptyMap())

        val builder = IconGenerator(context, options, pack1, pack2)
        builder.generateIcon(application) { _, newIcon ->
            icon = newIcon
        }

        return icon
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
        val entry = iconPackAppFilterElement.entries.find { it.key.packageName == iconPackageName }

        val packApps = entry?.value ?: listOf()
        calendarIcon = appMan.getCalendarApplications(installedApplications, packApps)
        calendarIconsDrawable =
            appMan.getCalendarFromAppFilterElements(
                iconPackageName,
                packApps
            )
    }

    @Suppress(SuppressRedundantSuspendModifier)
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

        installedApplications = appManager.getAllInstalledApplications()

        for (iconPack in iconPacks) {
            map[iconPack] = appManager.getAppFilterRawElements(iconPack.packageName, installedApplications)
        }

        iconPackAppFilterElement = map

        iconPackLoaded = true
    }

    suspend fun forceSync() {
        if (iconPackLoaded) {
            initializeIconPacks()
        }
    }

    private fun editApplication(oldApp: PackageInfoStruct, newApp: PackageInfoStruct) {
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
        val entry = iconPackAppFilterElement.entries.find { it.key.packageName == iconPack } ?: return emptyMap()

        val apps = entry.value

        return appManager.getDrawableFromAppFilterElements(
            iconPack,
            installedApplications,
            apps
        )
    }

    private fun getIconPackAppDrawable(app: InstalledApplication, iconPack: String): Map<InstalledApplication, ResourceDrawable> {
        if (iconPack == "") return emptyMap()
        val entry = iconPackAppFilterElement.entries.find { it.key.packageName == iconPack } ?: return emptyMap()

        val apps = entry.value

        return appManager.getDrawableFromAppFilterElements(
            iconPack,
            listOf(app),
            apps
        )
    }

    fun getIconPackIcons(iconPackName: String, options: GenerationOptions, drawables: List<ResourceDrawable>): List<ExportableIcon> {
        val exportDrawables = mutableListOf<ExportableIcon>()

        val pack = IconPackContainer("", emptyMap())

        val builder = IconGenerator(context, options, pack, pack)
        for (drawable in drawables) {
            exportDrawables.add(builder.colorizeFromIconPack(iconPackName, drawable))
        }

        return exportDrawables
    }

    fun getIconPackDropdownIcons(application: InstalledApplication?): Map<String, ResourceDrawable> {
        val map = mutableMapOf<String, ResourceDrawable>()

        for (pack in iconPacks) {
            if (application == null) {
                val icon = appManager.getResIcon(pack.packageName, pack.iconID)

                if (icon != null) {
                    map[pack.packageName] = ResourceDrawable(pack.iconID, icon)
                }
            } else {
                val icons = getIconPackAppDrawable(application, pack.packageName)

                if (icons.isNotEmpty()) {
                    map[pack.packageName] = icons[application]!!
                }
            }
        }

        return map
    }

    data class BuiltIconPack(
        val uri: Uri,
        val packageName: String,
        val canBeInstalled: Boolean
    )
}