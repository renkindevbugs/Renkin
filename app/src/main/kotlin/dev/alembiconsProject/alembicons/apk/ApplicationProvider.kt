package dev.alembiconsProject.alembicons.apk

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.data.CalendarIconsKey
import dev.alembiconsProject.alembicons.data.DbApplication
import dev.alembiconsProject.alembicons.data.RenkinPackRepository
import dev.alembiconsProject.alembicons.data.ExportThemedKey
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.ImageEdit
import dev.alembiconsProject.alembicons.data.InstalledApplication
import dev.alembiconsProject.alembicons.data.PrimaryIconPackKey
import dev.alembiconsProject.alembicons.data.Source
import dev.alembiconsProject.alembicons.data.getBooleanValue
import dev.alembiconsProject.alembicons.data.getDefaultBackgroundColor
import dev.alembiconsProject.alembicons.data.getDefaultIconColor
import dev.alembiconsProject.alembicons.data.getStringValue
import dev.alembiconsProject.alembicons.drawable.BitmapIconDrawable
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
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
import dev.alembiconsProject.alembicons.xml.XmlDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApplicationProvider(private val context: Context) {
    var applicationList: List<PackageInfoStruct> by mutableStateOf(listOf())
        private set
    // Icon-pack data (packs, app-filter elements, calendar icons) lives in
    // IconPackRepository; iconPacks / iconPackLoaded delegate to it so existing UI reads
    // stay reactive (the repo backs them with Compose state).
    val iconPacks: List<IconPack> get() = iconPackRepo.iconPacks
    val iconPackLoaded: Boolean get() = iconPackRepo.iconPackLoaded
    var applicationsLoaded: Boolean by mutableStateOf(false)
        private set

    var defaultColor: Color = Color.Unspecified

    private val renkinPackRepo = RenkinPackRepository(context)
    private val iconPackRepo = IconPackRepository(context)

    private var am: ApplicationManager? = null
    private val appManager: ApplicationManager
        get() {
            if (am == null) am = ApplicationManager(context)
            return am!!
        }

    suspend fun initialize() {
        initializeApplications()
        initializeIconPacks()
        initializeRenkinPack()
    }

    suspend fun initializeApplications() = withContext(Dispatchers.Default) {
        val apps = appManager.getAllInstalledApps()
        apps.sort()

        applicationList = apps.toList()
        applicationsLoaded = true
    }

    suspend fun initializeIconPacks() = iconPackRepo.load()

    suspend fun initializeRenkinPack() {
        loadRenkinPack()
    }

    suspend fun retrieveOtherIcons(preferences: Preferences) = withContext(Dispatchers.Default) {
        val iconPackageName = preferences.getStringValue(PrimaryIconPackKey)
        val retrieveCalendarIcon = preferences.getBooleanValue(CalendarIconsKey)

        if (iconPackageName != "" && retrieveCalendarIcon) {
            iconPackRepo.retrieveCalendarIcons(iconPackageName)
        }
    }

    suspend fun refreshIcon(application: PackageInfoStruct, preferences: Preferences) = withContext(Dispatchers.Default) {
        // A newly installed app always gets its icon (re)generated
        val genOptions = GenerationOptions.fromPreferences(preferences, context, override = true)
        refreshIcon(application, genOptions)
    }

    private fun refreshIcon(application: PackageInfoStruct, options: GenerationOptions) {
        val primaryIconPackApps = iconPackRepo.getAppDrawables(options.primaryIconPack)
        val secondaryIconPackApps = iconPackRepo.getAppDrawables(options.secondaryIconPack)

        val pack1 = IconPackContainer(options.primaryIconPack, primaryIconPackApps)
        val pack2 = IconPackContainer(options.secondaryIconPack, secondaryIconPackApps)

        val builder = IconGenerator(context, options, pack1, pack2)
        builder.generateIcon(application) { app, icon ->
            editApplication(app, app.changeExport(icon))
        }
    }

    suspend fun refreshIcons(preferences: Preferences) = withContext(Dispatchers.Default) {
        var opt = GenerationOptions.fromPreferences(preferences, context)
        val retrieveCalendarIcon = preferences.getBooleanValue(CalendarIconsKey)

        val primaryIconPackApps = iconPackRepo.getAppDrawables(opt.primaryIconPack)
        val secondaryIconPackApps = iconPackRepo.getAppDrawables(opt.secondaryIconPack)

        if (opt.primaryIconPack != "" && retrieveCalendarIcon) {
            iconPackRepo.retrieveCalendarIcons(opt.primaryIconPack)
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

    suspend fun getIcon(application: PackageInfoStruct, options: GenerationOptions, customIcon: ResourceDrawable? = null): IconPackDrawable? =
        withContext(Dispatchers.Default) {
            var icon: IconPackDrawable? = null

            val primaryIconPackApps = iconPackRepo.getAppDrawables(options.primaryIconPack)

            val pack1 = IconPackContainer(options.primaryIconPack, primaryIconPackApps)
            val pack2 = IconPackContainer("", emptyMap())

            val builder = IconGenerator(context, options, pack1, pack2)
            builder.generateIcon(application, customIcon) { _, newIcon ->
                icon = newIcon
            }

            icon
        }

    /**
     * Builds the icon a specific pack provides for an app, by drawable name — used by the
     * icon-watch apply modal to preview/apply a suggested icon. No extra modifier is applied.
     */
    suspend fun getIconFromPackDrawable(
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
    suspend fun applyModifier(icon: IconPackDrawable, options: GenerationOptions): IconPackDrawable =
        withContext(Dispatchers.Default) {
            val pack = IconPackContainer("", emptyMap())
            val builder = IconGenerator(context, options, pack, pack)
            builder.applyModifier(icon, options.primaryImageEdit)
        }

    suspend fun buildAndSignIconPack(preferences: Preferences, textMethod: (text: String) -> Unit): BuiltIconPack =
        withContext(Dispatchers.Default) {
            val themed = preferences.getBooleanValue(ExportThemedKey)
            val iconColor = preferences.getDefaultIconColor(context)
            val bgColor = preferences.getDefaultBackgroundColor(context)

            val iconPackGenerator = IconPackBuilder(
                context,
                applicationList,
                iconPackRepo.calendarIcon,
                iconPackRepo.calendarIconsDrawable
            )
            val canBeInstalled = iconPackGenerator.canBeInstalled() // must be called before build and sign
            val apk = iconPackGenerator.buildAndSign(themed, iconColor.toHexString(), bgColor.toHexString(), textMethod)

            BuiltIconPack(apk, iconPackGenerator.getIconPackName(), canBeInstalled)
        }

    suspend fun installIconPack(iconPack: BuiltIconPack): Boolean = withContext(Dispatchers.Default) {
        var success = false

        if (iconPack.canBeInstalled) {
            success = ApkInstaller(context).install(iconPack.uri)
        } else {
            if (ApkUninstaller(context).uninstall(iconPack.packageName)) {
                success = ApkInstaller(context).install(iconPack.uri)
            }
        }

        saveRenkinPack()

        success
    }

    private suspend fun loadRenkinPack() = withContext(Dispatchers.Default) {
        val dbApps = renkinPackRepo.getAll()
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
    }

    private suspend fun saveRenkinPack() {
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

        renkinPackRepo.replaceAll(dbApps)
    }

    suspend fun forceSync() {
        if (iconPackRepo.iconPackLoaded) {
            iconPackRepo.load()
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

    suspend fun getIconPackIcons(iconPackName: String, options: GenerationOptions, drawables: List<ResourceDrawable>): Map<ResourceDrawable, IconPackDrawable?> =
        withContext(Dispatchers.Default) {
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

            exportDrawables
        }

    suspend fun getIconPackDropdownIcons(application: InstalledApplication?): Map<String, ResourceDrawable> =
        iconPackRepo.getDropdownIcons(application)

    suspend fun clearIcons() = withContext(Dispatchers.Default) {
        for (app in applicationList) {
            editApplication(app, app.changeExport(null))
        }
        // Persist the cleared state, otherwise the saved pack reloads the icons on the
        // next launch and "Remove icons" looks like it did nothing.
        saveRenkinPack()
    }

    /** Keys ("package/activity") of the apps stored in the last built/saved pack. */
    suspend fun getSavedPackKeys(): Set<String> = withContext(Dispatchers.Default) {
        renkinPackRepo.getAll().map { "${it.packageName}/${it.activityName}" }.toSet()
    }

    data class BuiltIconPack(
        val uri: Uri,
        val packageName: String,
        val canBeInstalled: Boolean
    )
}