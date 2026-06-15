package dev.alembiconsProject.alembicons.apk

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.constants.SuppressRedundantSuspendModifier
import dev.alembiconsProject.alembicons.data.AlchemiconPackDatabase
import dev.alembiconsProject.alembicons.data.CalendarIconsKey
import dev.alembiconsProject.alembicons.data.DbApplication
import dev.alembiconsProject.alembicons.data.ExportThemedKey
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.ImageEdit
import dev.alembiconsProject.alembicons.data.InstalledApplication
import dev.alembiconsProject.alembicons.data.PrimaryIconPackKey
import dev.alembiconsProject.alembicons.data.RawElement
import dev.alembiconsProject.alembicons.data.Source
import dev.alembiconsProject.alembicons.data.getBooleanValue
import dev.alembiconsProject.alembicons.data.getDefaultBackgroundColor
import dev.alembiconsProject.alembicons.data.getDefaultIconColor
import dev.alembiconsProject.alembicons.data.getStringValue
import dev.alembiconsProject.alembicons.drawable.BitmapIconDrawable
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.drawable.ImageVectorDrawable
import dev.alembiconsProject.alembicons.drawable.InsetIconDrawable
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable
import dev.alembiconsProject.alembicons.extension.bitmapFromBase64
import dev.alembiconsProject.alembicons.icon.creator.GenerationOptions
import dev.alembiconsProject.alembicons.icon.creator.IconGenerator
import dev.alembiconsProject.alembicons.icon.creator.IconPackContainer
import dev.alembiconsProject.alembicons.icon.parser.XmlNodeParser
import dev.alembiconsProject.alembicons.packages.ApplicationManager
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import dev.alembiconsProject.alembicons.ui.supportDynamicColors
import dev.alembiconsProject.alembicons.ui.toHexString
import dev.alembiconsProject.alembicons.vector.VectorParser
import dev.alembiconsProject.alembicons.xml.XmlDecoder

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
        // A newly installed app always gets its icon (re)generated
        val genOptions = GenerationOptions.fromPreferences(preferences, context, override = true)
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
        var opt = GenerationOptions.fromPreferences(preferences, context)
        val retrieveCalendarIcon = preferences.getBooleanValue(CalendarIconsKey)

        val primaryIconPackApps = getIconPackAppDrawables(opt.primaryIconPack)
        val secondaryIconPackApps = getIconPackAppDrawables(opt.secondaryIconPack)

        if (opt.primaryIconPack != "" && retrieveCalendarIcon) {
            retrieveCalendarIcons(opt.primaryIconPack)
        }

        // Themed icons on Android 12+ are recoloured with the system dynamic palette
        if (opt.themed && supportDynamicColors()) {
            opt = opt.copy(
                color = context.resources.getColor(R.color.icon_color, null),
                bgColor = context.resources.getColor(R.color.icon_background_color, null)
            )
        }

        val pack1 = IconPackContainer(opt.primaryIconPack, primaryIconPackApps)
        val pack2 = IconPackContainer(opt.secondaryIconPack, secondaryIconPackApps)

        val builder = IconGenerator(context, opt, pack1, pack2)
        builder.generateIcons(applicationList) { application, icon ->
            editApplication(application, application.changeExport(icon))
        }
    }

    fun getIcon(application: PackageInfoStruct, options: GenerationOptions, customIcon: ResourceDrawable? = null): IconPackDrawable? {
        var icon: IconPackDrawable? = null

        val primaryIconPackApps = getIconPackAppDrawables(options.primaryIconPack)

        val pack1 = IconPackContainer(options.primaryIconPack, primaryIconPackApps)
        val pack2 = IconPackContainer("", emptyMap())

        val builder = IconGenerator(context, options, pack1, pack2)
        builder.generateIcon(application, customIcon) { _, newIcon ->
            icon = newIcon
        }

        return icon
    }

    /**
     * Builds the icon a specific pack provides for an app, by drawable name — used by the
     * icon-watch apply modal to preview/apply a suggested icon. No extra modifier is applied.
     */
    fun getIconFromPackDrawable(
        application: PackageInfoStruct,
        packPackage: String,
        drawableName: String,
        options: GenerationOptions
    ): IconPackDrawable? {
        val ids = appManager.getIconPackDrawableIds(packPackage, listOf(drawableName))
        val resource = appManager.getIconPackDrawables(packPackage, ids).firstOrNull() ?: return null
        val packOptions = options.copy(
            primarySource = Source.ICON_PACK,
            primaryImageEdit = ImageEdit.NONE,
            primaryIconPack = packPackage
        )
        return getIcon(application, packOptions, resource)
    }

    /** Applies the modifier from [options] to an already-built icon (e.g. a hand-edited vector). */
    fun applyModifier(icon: IconPackDrawable, options: GenerationOptions): IconPackDrawable {
        val pack = IconPackContainer("", emptyMap())
        val builder = IconGenerator(context, options, pack, pack)
        return builder.applyModifier(icon, options.primaryImageEdit)
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
                    XmlNodeParser.parse(context.resources, nodes, defaultColor)
                } else {
                    BitmapIconDrawable(bitmapFromBase64(dbApp.drawable), dbApp.isAdaptiveIcon)
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
            if (app.createdIcon != null) {
                val isXml = app.createdIcon !is BitmapIconDrawable

                dbApps.add(
                    DbApplication(
                        app.packageName,
                        app.activityName,
                        app.createdIcon.isAdaptiveIcon(),
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

    fun getIconPackIcons(iconPackName: String, options: GenerationOptions, drawables: List<ResourceDrawable>): Map<ResourceDrawable, IconPackDrawable?> {
        val exportDrawables = mutableMapOf<ResourceDrawable, IconPackDrawable?>()

        val pack = IconPackContainer("", emptyMap())

        val builder = IconGenerator(context, options, pack, pack)
        for (drawable in drawables) {
            // One broken icon must not take the whole pack down (#119)
            exportDrawables[drawable] = try {
                builder.colorizeFromIconPack(iconPackName, drawable)
            } catch (_: Exception) {
                null
            }
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

    fun clearIcons() {
        for (app in applicationList) {
            editApplication(app, app.changeExport(null))
        }
    }

    data class BuiltIconPack(
        val uri: Uri,
        val packageName: String,
        val canBeInstalled: Boolean
    )
}