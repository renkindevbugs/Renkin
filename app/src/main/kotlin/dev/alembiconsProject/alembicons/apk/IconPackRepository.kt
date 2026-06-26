package dev.alembiconsProject.alembicons.apk

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.InstalledApplication
import dev.alembiconsProject.alembicons.data.RawCalendar
import dev.alembiconsProject.alembicons.data.RawElement
import dev.alembiconsProject.alembicons.data.toComponentInfo
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable
import dev.alembiconsProject.alembicons.packages.ApplicationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns everything about installed icon packs: the pack list, their app-filter elements,
 * the per-app drawables resolved from them, and the calendar icons derived from them.
 * [ApplicationProvider] delegates here; icon generation and pack building query it.
 *
 * [iconPacks] / [iconPackLoaded] are Compose state so the UI re-reads them once packs
 * finish loading (e.g. the edit dialog's icon-pack browser) instead of capturing the
 * empty initial list.
 */
class IconPackRepository(private val context: Context) {
    var iconPacks: List<IconPack> by mutableStateOf(listOf())
        private set
    var iconPackLoaded: Boolean by mutableStateOf(false)
        private set

    private var appFilterElements: Map<IconPack, List<RawElement>> = emptyMap()
    private var installedApplications: List<InstalledApplication> = listOf()

    var calendarIcon: Map<InstalledApplication, String> = mapOf()
        private set
    var calendarIconsDrawable: Map<String, Drawable> = emptyMap()
        private set

    private val appManager: ApplicationManager by lazy { ApplicationManager(context) }

    suspend fun load() = withContext(Dispatchers.Default) {
        iconPackLoaded = false
        // Drop our own generated pack — it only ever holds icons we just built, so offering
        // it as an icon source (or a watch target) is pointless and just clutters the lists.
        iconPacks = appManager.getIconPacks().filter { it.packageName != IconPackBuilder.PACKAGE_NAME }
        loadAppFilterElements()
    }

    private fun loadAppFilterElements() {
        val map = mutableMapOf<IconPack, List<RawElement>>()

        installedApplications = appManager.getAllInstalledApplications()

        for (iconPack in iconPacks) {
            map[iconPack] = appManager.getAppFilterRawElements(iconPack.packageName, installedApplications)
        }

        appFilterElements = map
        iconPackLoaded = true
    }

    /** Drawables every installed app has in [iconPack], keyed by app. */
    fun getAppDrawables(iconPack: String): Map<InstalledApplication, ResourceDrawable> {
        if (iconPack == "") return emptyMap()
        val entry = appFilterElements.entries.find { it.key.packageName == iconPack } ?: return emptyMap()

        return appManager.getDrawableFromAppFilterElements(
            iconPack,
            installedApplications,
            entry.value
        )
    }

    private fun getAppDrawable(app: InstalledApplication, iconPack: String): Map<InstalledApplication, ResourceDrawable> {
        if (iconPack == "") return emptyMap()
        val entry = appFilterElements.entries.find { it.key.packageName == iconPack } ?: return emptyMap()

        return appManager.getDrawableFromAppFilterElements(
            iconPack,
            listOf(app),
            entry.value
        )
    }

    fun retrieveCalendarIcons(iconPackageName: String) {
        val appMan = ApplicationManager(context)
        val entry = appFilterElements.entries.find { it.key.packageName == iconPackageName }

        val packApps = entry?.value ?: listOf()
        calendarIcon = appMan.getCalendarApplications(installedApplications, packApps)
        calendarIconsDrawable =
            appMan.getCalendarFromAppFilterElements(
                iconPackageName,
                packApps
            )
    }

    /**
     * Returns the calendar prefix for [app] in [iconPackageName] (e.g. `"google_cal_"`),
     * or null if the pack doesn't declare a calendar entry for that app.
     * Reads from the already-loaded [appFilterElements] — no I/O.
     */
    fun calendarPrefixFor(app: InstalledApplication, iconPackageName: String): String? {
        val entry = appFilterElements.entries.find { it.key.packageName == iconPackageName } ?: return null
        val component = app.toComponentInfo()
        return entry.value.filterIsInstance<RawCalendar>().find { it.component == component }?.prefix
    }

    /**
     * Returns calendar icons (app→prefix map and prefix+day→drawable map) for the given
     * [apps] in [iconPackageName], without touching the stored [calendarIcon] / [calendarIconsDrawable].
     * Used during build for apps that opted in per-app, independently of the global switch.
     */
    fun calendarDataFor(
        iconPackageName: String,
        apps: List<InstalledApplication>
    ): Pair<Map<InstalledApplication, String>, Map<String, Drawable>> {
        if (apps.isEmpty()) return emptyMap<InstalledApplication, String>() to emptyMap()
        val entry = appFilterElements.entries.find { it.key.packageName == iconPackageName }
            ?: return emptyMap<InstalledApplication, String>() to emptyMap()

        val requestedComponents = apps.map { it.toComponentInfo() }.toSet()
        val relevant = entry.value.filter { it is RawCalendar && it.component in requestedComponents }

        val appMan = ApplicationManager(context)
        val icons = appMan.getCalendarApplications(apps, relevant)
        val drawables = appMan.getCalendarFromAppFilterElements(iconPackageName, relevant)
        return icons to drawables
    }

    /** One representative icon per pack (the pack's own icon, or the app's icon in that pack). */
    suspend fun getDropdownIcons(application: InstalledApplication?): Map<String, ResourceDrawable> =
        withContext(Dispatchers.Default) {
            val map = mutableMapOf<String, ResourceDrawable>()

            for (pack in iconPacks) {
                if (application == null) {
                    val icon = appManager.getResIcon(pack.packageName, pack.iconID)

                    if (icon != null) {
                        map[pack.packageName] = ResourceDrawable(pack.iconID, icon)
                    }
                } else {
                    val icons = getAppDrawable(application, pack.packageName)

                    if (icons.isNotEmpty()) {
                        map[pack.packageName] = icons[application]!!
                    }
                }
            }

            map
        }
}
