package dev.alembiconsProject.alembicons.apk

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.data.CalendarIconsKey
import dev.alembiconsProject.alembicons.data.ExportThemedKey
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.InstalledApplication
import dev.alembiconsProject.alembicons.data.PrimaryIconPackKey
import dev.alembiconsProject.alembicons.data.SecondaryIconPackKey
import dev.alembiconsProject.alembicons.data.getBooleanValue
import dev.alembiconsProject.alembicons.data.getDefaultBackgroundColor
import dev.alembiconsProject.alembicons.data.getDefaultIconColor
import dev.alembiconsProject.alembicons.data.getStringValue
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable
import dev.alembiconsProject.alembicons.icon.creator.GenerationOptions
import dev.alembiconsProject.alembicons.packages.ApplicationManager
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import dev.alembiconsProject.alembicons.extension.toHexString
import dev.alembiconsProject.alembicons.packages.supportDynamicColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApplicationProvider(private val context: Context) {
    // A SnapshotStateList (not mutableStateOf(List)) so editing one app's icon is an O(1)
    // in-place set instead of copying the whole list — refreshIcons edits every app, so the
    // old copy-per-edit made it O(n²). Exposed read-only as List; the UI still observes it.
    private val _applicationList = mutableStateListOf<PackageInfoStruct>()
    val applicationList: List<PackageInfoStruct> get() = _applicationList
    // Icon-pack data (packs, app-filter elements, calendar icons) lives in
    // IconPackRepository; iconPacks / iconPackLoaded delegate to it so existing UI reads
    // stay reactive (the repo backs them with Compose state).
    val iconPacks: List<IconPack> get() = iconPackRepo.iconPacks
    val iconPackLoaded: Boolean get() = iconPackRepo.iconPackLoaded
    var applicationsLoaded: Boolean by mutableStateOf(false)
        private set

    var defaultColor: Color = Color.Unspecified

    private val renkinPackStore = RenkinPackStore(context)
    private val iconPackRepo = IconPackRepository(context)
    private val iconGenService = IconGenerationService(context, iconPackRepo)

    private val appManager: ApplicationManager by lazy { ApplicationManager(context) }

    suspend fun initialize() {
        initializeApplications()
        initializeIconPacks()
        initializeRenkinPack()
    }

    suspend fun initializeApplications() = withContext(Dispatchers.Default) {
        val apps = appManager.getAllInstalledApps()
        apps.sort()

        _applicationList.clear()
        _applicationList.addAll(apps.asList())
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
        iconGenService.refreshIcon(application, genOptions) { app, icon ->
            editApplication(app, app.changeExport(icon))
        }
    }

    suspend fun refreshIcons(preferences: Preferences) = withContext(Dispatchers.Default) {
        var opt = GenerationOptions.fromPreferences(preferences, context)
        val retrieveCalendarIcon = preferences.getBooleanValue(CalendarIconsKey)

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

        // Iterate a snapshot copy: the callback edits the live list in place, and iterating
        // the SnapshotStateList itself while mutating it would throw.
        iconGenService.refreshIcons(applicationList.toList(), opt) { application, icon ->
            editApplication(application, application.changeExport(icon))
        }
    }

    suspend fun getIcon(application: PackageInfoStruct, options: GenerationOptions, customIcon: ResourceDrawable? = null): IconPackDrawable? =
        iconGenService.getIcon(application, options, customIcon)

    /**
     * Builds the icon a specific pack provides for an app, by drawable name — used by the
     * icon-watch apply modal to preview/apply a suggested icon. No extra modifier is applied.
     */
    suspend fun getIconFromPackDrawable(
        application: PackageInfoStruct,
        packPackage: String,
        drawableName: String,
        options: GenerationOptions
    ): IconPackDrawable? =
        iconGenService.getIconFromPackDrawable(application, packPackage, drawableName, options)

    /** Applies the modifier from [options] to an already-built icon (e.g. a hand-edited vector). */
    suspend fun applyModifier(icon: IconPackDrawable, options: GenerationOptions): IconPackDrawable =
        iconGenService.applyModifier(icon, options)

    suspend fun buildAndSignIconPack(preferences: Preferences, textMethod: (text: String) -> Unit): BuiltIconPack =
        withContext(Dispatchers.Default) {
            val themed = preferences.getBooleanValue(ExportThemedKey)
            val iconColor = preferences.getDefaultIconColor(context)
            val bgColor = preferences.getDefaultBackgroundColor(context)
            val primaryPackName = preferences.getStringValue(PrimaryIconPackKey)

            // Per-app calendar: group opted-in apps by which pack they chose their icon from,
            // then load the day-1..31 drawables from THAT pack (not the global primary).
            // Per-app entries override global calendar for the same app.
            var perAppIcons = emptyMap<InstalledApplication, String>()
            var perAppDrawables = emptyMap<String, android.graphics.drawable.Drawable>()

            applicationList
                .filter { it.calendarEnabled && !it.calendarPrefix.isNullOrEmpty() && !it.calendarPackName.isNullOrEmpty() }
                .groupBy { it.calendarPackName!! }
                .forEach { (packName, apps) ->
                    val appsWithPrefixes = apps.map { it.toInstalledApplication() to it.calendarPrefix!! }
                    val (icons, drawables) = iconPackRepo.calendarDataForPrefixes(packName, appsWithPrefixes)
                    perAppIcons = perAppIcons + icons
                    perAppDrawables = perAppDrawables + drawables
                }

            // Global calendar for apps that did NOT opt in per-app
            val perAppPackages = perAppIcons.keys.map { it.packageName }.toSet()
            val allCalendarIcons = iconPackRepo.calendarIcon.filter { it.key.packageName !in perAppPackages } + perAppIcons
            val allCalendarDrawables = iconPackRepo.calendarIconsDrawable + perAppDrawables

            val iconPackGenerator = IconPackBuilder(
                context,
                applicationList,
                allCalendarIcons,
                allCalendarDrawables
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

    private suspend fun loadRenkinPack() {
        val saved = renkinPackStore.load(defaultColor)
        if (saved.isEmpty()) return

        for (app in applicationList.toList()) {
            val entry = saved["${app.packageName}/${app.activityName}"] ?: continue
            val updated = when {
                entry.icon != null -> app.changeExport(entry.icon).changeCalendar(entry.calendarEnabled, entry.calendarPrefix, entry.calendarPackName)
                else -> app.changeCalendar(entry.calendarEnabled, entry.calendarPrefix, entry.calendarPackName)
            }
            editApplication(app, updated)
        }
    }

    /** Sets the calendar-day-icons flag, prefix, and source pack for [app]. */
    fun setCalendar(app: PackageInfoStruct, enabled: Boolean, calendarPrefix: String?, calendarPackName: String?) {
        val index = _applicationList.indexOfFirst {
            it.packageName == app.packageName && it.activityName == app.activityName
        }
        if (index >= 0) editApplication(index, _applicationList[index].changeCalendar(enabled, calendarPrefix, calendarPackName))
    }

    private suspend fun saveRenkinPack() = renkinPackStore.save(applicationList)

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
        _applicationList[index] = newApp
    }

    suspend fun getIconPackIcons(iconPackName: String, options: GenerationOptions, drawables: List<ResourceDrawable>): Map<ResourceDrawable, IconPackDrawable?> =
        iconGenService.getIconPackIcons(iconPackName, options, drawables)

    suspend fun getIconPackDropdownIcons(application: InstalledApplication?): Map<String, ResourceDrawable> =
        iconPackRepo.getDropdownIcons(application)

    suspend fun clearIcons() = withContext(Dispatchers.Default) {
        // Snapshot copy: editApplication mutates the live list in place.
        for (app in applicationList.toList()) {
            editApplication(app, app.changeExport(null))
        }
        // Persist the cleared state, otherwise the saved pack reloads the icons on the
        // next launch and "Remove icons" looks like it did nothing.
        saveRenkinPack()
    }

    /** Keys ("package/activity") of the apps stored in the last built/saved pack. */
    suspend fun getSavedPackKeys(): Set<String> = renkinPackStore.savedKeys()

    data class BuiltIconPack(
        val uri: Uri,
        val packageName: String,
        val canBeInstalled: Boolean
    )
}