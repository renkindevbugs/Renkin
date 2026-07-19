package dev.renkinProject.renkin.packages

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.XmlResourceParser
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.UserManager
import androidx.core.content.pm.PackageInfoCompat
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawCalendar
import dev.renkinProject.renkin.data.RawDynamicClock
import dev.renkinProject.renkin.data.RawElement
import dev.renkinProject.renkin.data.IconPackFallback
import dev.renkinProject.renkin.data.RawItem
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.drawable.hasValidDimensions
import dev.renkinProject.renkin.extension.getDrawableOrNull
import dev.renkinProject.renkin.extension.getIdentifierByName
import dev.renkinProject.renkin.extension.getXmlOrNull
import dev.renkinProject.renkin.extension.toDrawable
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal fun launcherIconOrFallback(activityIcon: Drawable?, applicationIcon: () -> Drawable): Drawable =
    activityIcon ?: applicationIcon()

internal fun launcherIconIdOrFallback(activityIconId: Int?, applicationIconId: Int): Int =
    activityIconId?.takeIf { it != 0 } ?: applicationIconId

internal data class NamedResourceDrawable(val name: String, val resource: ResourceDrawable)

internal interface PackBrowserDataSource {
    fun getIconPackDrawableNames(iconPackName: String): List<String>
    fun getIconPackDrawableEntries(iconPackName: String, drawableNames: List<String>): List<NamedResourceDrawable>
    fun getAppFilterRawElements(iconPackName: String, applications: List<InstalledApplication>): List<RawElement>
}

/**
 * Resolves drawable names independently. Missing or malformed entries are skipped without
 * shifting the name→resource association of every healthy entry that follows them.
 */
internal fun resolveNamedDrawables(
    names: List<String>,
    resolveId: (String) -> Int,
    loadDrawable: (Int) -> Drawable?
): List<NamedResourceDrawable> {
    val seenIds = mutableSetOf<Int>()
    return names.mapNotNull { name ->
        val id = runCatching { resolveId(name) }.getOrNull() ?: return@mapNotNull null
        if (id <= 0 || !seenIds.add(id)) return@mapNotNull null
        val drawable = runCatching { loadDrawable(id) }.getOrNull() ?: return@mapNotNull null
        NamedResourceDrawable(name, ResourceDrawable(id, drawable))
    }
}

internal class ApplicationManager(private val ctx: Context) : PackBrowserDataSource {

    companion object {
        /**
         * The night mode Renkin's UI currently displays — set by MainActivity when the
         * Compose theme resolves (the in-app dark-mode choice never touches the process
         * configuration). Null until the first composition; pack resources then resolve
         * with the system configuration, same as before.
         */
        @Volatile
        var displayedNightMode: Boolean? = null
    }
    private val pm = ctx.packageManager

    /**
     * Lightweight component references (package/activity/icon id) for every launcher entry.
     * Deliberately loads NO labels or drawables — callers that only need identity (appfilter
     * matching, the watch checker) must not pay for the full [getAllInstalledApps] scan.
     */
    fun getAllInstalledApplications(): List<InstalledApplication> {
        val userManager = ctx.getSystemService(Context.USER_SERVICE) as UserManager
        val apps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

        val packs = linkedSetOf<InstalledApplication>()
        for (user in userManager.userProfiles) {
            for (app in apps.getActivityList(null, user)) {
                @Suppress("DEPRECATION")
                val iconID = launcherIconIdOrFallback(
                    runCatching { pm.getActivityInfo(app.componentName, 0).iconResource }.getOrNull(),
                    app.applicationInfo.icon
                )
                packs.add(
                    InstalledApplication(app.componentName.packageName, app.componentName.className, iconID)
                )
            }
        }
        return packs.toList()
    }

    suspend fun getAllInstalledApps(): Array<PackageInfoStruct> = coroutineScope {
        val userManager = ctx.getSystemService(Context.USER_SERVICE) as UserManager
        val apps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

        // The per-app work — labels, an English-locale resource lookup and the icon drawable —
        // is a few binder/resource round-trips each. Sequentially that dominates cold start on
        // large app lists, so the apps resolve in parallel; order is restored by awaitAll.
        val structs = userManager.userProfiles
            .flatMap { user -> apps.getActivityList(null, user) }
            .map { app ->
                async(Dispatchers.Default) {
                    val appName = app.applicationInfo.loadLabel(pm).toString()
                    val originalName = loadEnglishLabel(app.applicationInfo, appName)
                    val packageName = app.componentName.packageName
                    val activityName = app.componentName.className
                    // Launcher activities may override the application-level icon. Use the
                    // activity-specific artwork and resource id so Current/Application Icon
                    // previews match the exact component being edited.
                    val icon = launcherIconOrFallback(
                        runCatching { app.getIcon(ctx.resources.displayMetrics.densityDpi) }.getOrNull()
                    ) { app.applicationInfo.loadIcon(pm) }
                    @Suppress("DEPRECATION")
                    val iconID = launcherIconIdOrFallback(
                        runCatching { pm.getActivityInfo(app.componentName, 0).iconResource }.getOrNull(),
                        app.applicationInfo.icon
                    )

                    val icon2 = if (!icon.hasValidDimensions()) {
                        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).toDrawable(ctx.resources)
                    } else
                        icon

                    PackageInfoStruct(
                        appName,
                        packageName,
                        activityName,
                        icon2,
                        iconID,
                        originalName = originalName
                    )
                }
            }
            .awaitAll()

        // Set-based dedupe: the old List.contains check compared every pair (O(n²)).
        val deduped = linkedSetOf<PackageInfoStruct>()
        deduped.addAll(structs)
        deduped.toTypedArray()
    }

    /**
     * The app's English label. [ApplicationInfo.loadLabel] resolves the label against the
     * package's own resources using the *device* locale, so a context with an English
     * override doesn't change it. We instead read the label resource directly from a copy of
     * the package's resources whose configuration is forced to English. Apps with a
     * non-localized label (labelRes == 0) have no per-locale variant, so we fall back to
     * [fallback] (the already-loaded localized label).
     */
    private fun loadEnglishLabel(appInfo: ApplicationInfo, fallback: String): String {
        val labelRes = appInfo.labelRes
        if (labelRes == 0) return fallback
        return try {
            val res = pm.getResourcesForApplication(appInfo)
            val config = Configuration(res.configuration).also { it.setLocale(Locale.ENGLISH) }
            @Suppress("DEPRECATION")
            val englishRes = Resources(res.assets, res.displayMetrics, config)
            englishRes.getString(labelRes)
        } catch (e: Exception) {
            fallback
        }
    }

    fun getIconPacks(): List<IconPack> {
        return getIconPacks(Intent("org.adw.launcher.THEMES", null))
    }

    override fun getAppFilterRawElements(iconPackName: String, applications: List<InstalledApplication>): List<RawElement> {
        val res = getResources(iconPackName) ?: return emptyList()
        val xmlParser = getAppfilter(res, iconPackName)

        val components = applications.map { it.toComponentInfo() }

        if (xmlParser != null) {
            return getAppFilterRawElements(xmlParser, components)
        }

        return emptyList()
    }

    /** The drawable name [iconPackName]'s appfilter maps to [application]'s component, if any. */
    fun appFilterDrawableName(iconPackName: String, application: InstalledApplication): String? =
        runCatching { getAppFilterRawElements(iconPackName, listOf(application)) }
            .getOrDefault(emptyList())
            .filterIsInstance<RawItem>()
            .firstOrNull { it.component == application.toComponentInfo() }
            ?.drawableLink

    /**
     * Parses the pack's classic fallback styling (`<iconback>`, `<iconmask>`, `<iconupon>`,
     * `<scale>`) used to give a uniform look to apps the pack doesn't theme. Returns an empty
     * [IconPackFallback] when the pack declares none.
     */
    fun getIconPackFallback(iconPackName: String): IconPackFallback {
        val res = getResources(iconPackName) ?: return IconPackFallback()
        val xmlParser = getAppfilter(res, iconPackName) ?: return IconPackFallback()

        val backs = mutableListOf<String>()
        var mask: String? = null
        var upon: String? = null
        var scale = 1f

        // Collects every imgN attribute (img1, img2, …) an iconback/mask/upon tag declares.
        fun images(): List<String> = buildList {
            var i = 1
            while (true) {
                val v = xmlParser.getAttributeValue(null, "img$i") ?: break
                add(v)
                i++
            }
        }

        var type = xmlParser.eventType
        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                when (xmlParser.name) {
                    "iconback" -> backs.addAll(images())
                    "iconmask" -> mask = images().firstOrNull() ?: mask
                    "iconupon" -> upon = images().firstOrNull() ?: upon
                    "scale" -> xmlParser.getAttributeValue(null, "factor")?.toFloatOrNull()?.let { scale = it }
                }
            }
            type = xmlParser.next()
        }

        return IconPackFallback(backs, mask, upon, scale)
    }

    fun getDrawableFromAppFilterElements(iconPackName: String, applications: List<InstalledApplication>, elements: List<RawElement>): Map<InstalledApplication, ResourceDrawable> {
        val map = mutableMapOf<InstalledApplication, ResourceDrawable>()

        val drawables = getDrawableFromAppFilterElements(iconPackName, elements)

        for (drawable in drawables) {
            for (app in applications) {
                if (drawable.key == app.toComponentInfo()) {
                    map[app] = drawable.value
                }
            }
        }

        return map
    }

    private fun getDrawableFromAppFilterElements(iconPackName: String, elements: List<RawElement>): Map<String, ResourceDrawable> {
        val map = mutableMapOf<String, ResourceDrawable>()
        val res = getResources(iconPackName) ?: return map

        for (element in elements) {
            if (element is RawItem) {
                val resourceId = runCatching {
                    res.getIdentifierByName(element.drawableLink, "drawable", iconPackName)
                }.getOrNull() ?: continue

                if (resourceId > 0) {
                    val drawable = runCatching { getResIcon(res, resourceId) }.getOrNull()
                    if (drawable != null)
                        map[element.component] = ResourceDrawable(resourceId, drawable)
                }
            }
        }

        return map
    }

    override fun getIconPackDrawableNames(iconPackName: String): List<String> {
        val res = getResources(iconPackName) ?: return emptyList()
        val xmlParser = getDrawable(res, iconPackName) ?: return emptyList()

        val list = mutableListOf<String>()
        var type = xmlParser.eventType

        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                if (xmlParser.name == "item") {
                    val xmlDrawable = xmlParser.getAttributeValue(null, "drawable")

                    if (xmlDrawable != null) {
                        list.add(xmlDrawable)
                    }
                }
            }

            type = xmlParser.next()
        }

        // Icon packs often list the same drawable in multiple categories of drawable.xml.
        // Duplicates would produce duplicate resource ids and crash lazy lists keyed by id.
        return list.distinct()
    }

    override fun getIconPackDrawableEntries(iconPackName: String, drawableNames: List<String>): List<NamedResourceDrawable> {
        val res = getResources(iconPackName) ?: return emptyList()
        return resolveNamedDrawables(
            drawableNames,
            resolveId = { name -> res.getIdentifierByName(name, "drawable", iconPackName) },
            loadDrawable = { id -> getResIcon(res, id) }
        )
    }

    fun getCalendarApplications(applications: List<InstalledApplication>, elements: List<RawElement>): Map<InstalledApplication, String> {
        val map = mutableMapOf<InstalledApplication, String>()

        val calendarIcons = elements.filterIsInstance<RawCalendar>()

        for (calendar in calendarIcons) {
            for (app in applications) {
                if (calendar.component == app.toComponentInfo()) {
                    map[app] = calendar.prefix
                }
            }
        }

        return map
    }

    private fun getIconPacks(intent: Intent): List<IconPack> {
        val resolves = getResolves(intent)
        val iconPacks = mutableListOf<IconPack>()

        for (resolve in resolves) {
            try {
                val appName = resolve.activityInfo.applicationInfo.loadLabel(pm).toString()
                val packageName = resolve.activityInfo.packageName
                val iconID = resolve.activityInfo.applicationInfo.icon
                val pack = getPackage(packageName)

                if (pack != null) {
                    val versionCode = getVersionCode(pack)
                    val versionName = pack.versionName ?: ""
                    iconPacks.add(IconPack(packageName, appName, versionCode, versionName, iconID))
                }
            } catch (error: Exception) {
                // Broken metadata in one advertised theme must not hide every healthy pack.
                Log.error("ApplicationManager", "Skipping malformed icon pack activity", error)
            }
        }

        // A pack with several THEMES activities resolves several times — dedupe here at the
        // source instead of at every UI call site.
        return iconPacks.distinctBy { it.packageName }
    }

    private fun getAppFilterRawElements(xmlParser: XmlPullParser, components: List<String>): List<RawElement> {
        val list = mutableListOf<RawElement>()

        var type = xmlParser.eventType

        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                if (xmlParser.name == "item") {
                    val xmlDrawable = xmlParser.getAttributeValue(null, "drawable")
                    val xmlComponent = xmlParser.getAttributeValue(null, "component")

                    for (app in components) {
                        if (xmlComponent == app && xmlDrawable != null) {
                            list.add(RawItem(xmlComponent, xmlDrawable))
                            break
                        }
                    }
                }

                if (xmlParser.name == "calendar") {
                    val xmlPrefix = xmlParser.getAttributeValue(null, "prefix")
                    val xmlComponent = xmlParser.getAttributeValue(null, "component")

                    for (app in components) {
                        if (xmlComponent == app && xmlPrefix != null) {
                            list.add(RawCalendar(xmlComponent, xmlPrefix))
                            break
                        }
                    }
                }

                if (xmlParser.name == "dynamic-clock") {
                    val xmlDrawable = xmlParser.getAttributeValue(null, "drawable")
                    val xmlDefaultHour = xmlParser.getAttributeValue(null, "defaultHour")
                    val xmlDefaultMinute = xmlParser.getAttributeValue(null, "defaultMinute")
                    val xmlHourLayerIndex = xmlParser.getAttributeValue(null, "hourLayerIndex")
                    val xmlMinuteLayerIndex = xmlParser.getAttributeValue(null, "minuteLayerIndex")

                    if (xmlDrawable != null && xmlDefaultHour != null && xmlDefaultMinute != null
                        && xmlHourLayerIndex != null && xmlMinuteLayerIndex != null) {
                        list.add(
                            RawDynamicClock(
                                xmlDrawable,
                                xmlDefaultHour,
                                xmlDefaultMinute,
                                xmlHourLayerIndex,
                                xmlMinuteLayerIndex
                            )
                        )
                    }
                }
            }

            type = xmlParser.next()
        }

        return list
    }

    fun checkAppFilter(xmlParser: XmlPullParser): Array<String> {
        val badlyFormattedComponents = mutableListOf<String>()
        var type = xmlParser.eventType

        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                if (xmlParser.name == "item") {
                    val iconName = xmlParser.getAttributeValue(null, "drawable")
                    val componentInfo = xmlParser.getAttributeValue(null, "component")

                    if (iconName == null || componentInfo == null || !componentIsValid(componentInfo)) {
                        var item = ""
                        for (i in 0 until  xmlParser.attributeCount) {
                            item += "${xmlParser.getAttributeName(i)}=\"${xmlParser.getAttributeValue(i)}\" "
                        }
                        badlyFormattedComponents.add(item.trimEnd())
                    }
                }
            }

            type = xmlParser.next()
        }

        return badlyFormattedComponents.toTypedArray()
    }

    private fun componentIsValid(text: String): Boolean {
        var newText = text

        if (!text.startsWith("ComponentInfo", true))
            return false

        newText = newText.replace("(", "{")
        newText = newText.replace(")", "}")

        val firstSplit = newText.split("{")
        if (firstSplit.count() != 2)
            return false

        val secondSplit = firstSplit[1].split("}")
        if (secondSplit.count() != 2)
            return false

        val thirdSplit = secondSplit[0].split("/")
        return thirdSplit.count() >= 2
    }

    private fun getResolves(intent: Intent): List<ResolveInfo> {
        if (dev.renkinProject.renkin.packages.PackageVersion.is33OrMore())
            return pm.queryIntentActivities(intent, ResolveInfoFlags.of(0))
        return pm.queryIntentActivities(intent, 0)
    }

    private fun getAppfilter(res: Resources, packageName: String): XmlPullParser? {
        val xmlParser = getResAppfilter(res, packageName)

        if (xmlParser != null) return xmlParser
        return getAssetAppfilter(res)
    }

    private fun getResAppfilter(res: Resources, packageName: String): XmlPullParser? {
        return getResXml(res, packageName, "appfilter")
    }

    private fun getAssetAppfilter(res: Resources): XmlPullParser? {
        return getAssetXml(res, "appfilter.xml")
    }

    private fun getDrawable(res: Resources, packageName: String): XmlPullParser? {
        val xmlParser = getResDrawable(res, packageName)

        if (xmlParser != null) return xmlParser
        return getAssetDrawable(res)
    }

    private fun getResDrawable(res: Resources, packageName: String): XmlPullParser? {
        return getResXml(res, packageName, "drawable")
    }

    private fun getAssetDrawable(res: Resources): XmlPullParser? {
        return getAssetXml(res, "drawable.xml")
    }

    private fun getResXml(res: Resources, packageName: String, name: String): XmlPullParser? {
        val id = res.getIdentifierByName(name, "xml", packageName)
        if (id > 0) return res.getXml(id)

        return null
    }

    private fun getAssetXml(res: Resources, name: String): XmlPullParser? {
        val assets = res.assets.list("")

        if (assets != null && assets.contains(name)) {
            val xmlInStream = res.assets.open(name)
            val xmlParser = XmlPullParserFactory.newInstance().newPullParser()
            xmlParser.setInput(xmlInStream, "utf-8")

            return xmlParser
        }

        return null
    }

    private fun getResIcon(res: Resources, iconName: String, packageName: String, type: String = "drawable"): Drawable? {
        val id = res.getIdentifierByName(iconName, type, packageName)
        return getResIcon(res, id)
    }

    private fun getResIcon(res: Resources, resourceId: Int): Drawable? {
        if (resourceId > 0) return res.getDrawableOrNull(resourceId, null)
        return null
    }

    fun getResIcon(packageName: String, resourceId: Int): Drawable? {
        val res = getResources(packageName) ?: return null
        return getResIcon(res, resourceId)
    }

    fun getDrawableByName(packName: String, name: String): Drawable? {
        val res = getResources(packName) ?: return null
        return getResIcon(res, name, packName)
    }

    /** Cheap existence check (resolves the resource id only; no drawable decode). */
    fun hasDrawable(packName: String, name: String): Boolean {
        val res = getResources(packName) ?: return false
        return res.getIdentifierByName(name, "drawable", packName) > 0
    }

    fun getResources(packageName: String): Resources? {
        return try {
            val res = pm.getResourcesForApplication(packageName)
            withDisplayedNightMode(res)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Re-resolves [res] with the night mode Renkin's UI actually displays. The in-app theme
     * is a Compose override, not a configuration change — so a pack's mode-dependent colours
     * (Lawnicons' values-night foreground, for one) would otherwise resolve by the SYSTEM
     * mode and come out as the exact background colour of the mismatched in-app theme,
     * rendering its icons invisible.
     */
    private fun withDisplayedNightMode(res: Resources): Resources {
        val displayedNight = displayedNightMode ?: return res
        val config = Configuration(res.configuration)
        val resolvedNight =
            config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (resolvedNight == displayedNight) return res
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (displayedNight) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        @Suppress("DEPRECATION")
        return Resources(res.assets, res.displayMetrics, config)
    }
    
    fun getApp(packageName: String): ApplicationInfo? {
        return try {
            pm.getApplicationInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun getPackage(packageName: String): PackageInfo? {
        return try {
            pm.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun getPackageResourceType(packageName: String, resourceId: Int): String? {
        return try {
            val res = pm.getResourcesForApplication(packageName)
            res.getResourceTypeName(resourceId)
        } catch (e: Resources.NotFoundException) {
            null
        }
    }

    fun getPackageResourceXml(packageName: String, resourceId: Int): XmlResourceParser? {
        val res = getResources(packageName)
        return res?.getXmlOrNull(resourceId)
    }

    fun getVersionCode(pack: PackageInfo): Long {
        // PackageInfoCompat handles the longVersionCode (API 28+) vs versionCode split.
        return PackageInfoCompat.getLongVersionCode(pack)
    }
}
