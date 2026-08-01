package dev.renkinProject.renkin.packages

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
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
import dev.renkinProject.renkin.extension.getIdentifierByName
import dev.renkinProject.renkin.util.Log
import org.xmlpull.v1.XmlPullParser

internal fun matchDrawablesToApplications(
    applications: List<InstalledApplication>,
    drawablesByComponent: Map<String, ResourceDrawable>
): Map<InstalledApplication, ResourceDrawable> {
    val applicationsByComponent = applications.associateBy { it.toComponentInfo() }
    return buildMap {
        for ((component, drawable) in drawablesByComponent) {
            applicationsByComponent[component]?.let { app -> put(app, drawable) }
        }
    }
}

internal fun lastAppFilterItem(
    elements: List<RawElement>,
    component: String
): RawItem? =
    elements.asReversed()
        .firstOrNull { it is RawItem && it.component == component } as? RawItem

internal data class NamedResourceDrawable(val name: String, val resource: ResourceDrawable)

internal const val ICON_PACK_ACTION = "org.adw.launcher.THEMES"
internal const val CHANGES_WITH_MATERIAL_YOU_COLORS =
    "org.icontheme.CHANGES_WITH_MATERIAL_YOU_COLORS"

internal fun iconPackQueryIntent(
    materialYouColorsOnly: Boolean = false,
    packageName: String? = null
): Intent = Intent(ICON_PACK_ACTION, null).apply {
    if (materialYouColorsOnly) addCategory(CHANGES_WITH_MATERIAL_YOU_COLORS)
    if (packageName != null) setPackage(packageName)
}

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

internal class ApplicationManager(
    private val ctx: Context,
    private val resourceResolver: PackageResourceResolver = PackageResourceResolver(ctx)
) : PackBrowserDataSource {

    companion object {
        /**
         * The night mode Renkin's UI currently displays — set by MainActivity when the
         * Compose theme resolves (the in-app dark-mode choice never touches the process
         * configuration). Null until the first composition; pack resources then resolve
         * with the system configuration, same as before.
         */
        var displayedNightMode: Boolean?
            get() = PackageResourceResolver.displayedNightMode
            set(value) {
                PackageResourceResolver.displayedNightMode = value
            }
    }
    private val pm = ctx.packageManager

    fun getIconPacks(): List<IconPack> {
        val materialYouPacks = getResolves(
            iconPackQueryIntent(materialYouColorsOnly = true)
        ).mapTo(mutableSetOf()) { it.activityInfo.packageName }
        return getIconPacks(iconPackQueryIntent(), materialYouPacks)
    }

    /**
     * True only when the source pack explicitly advertises that its colours follow
     * Material You. Package names are deliberately not hard-coded: Lawnicons,
     * Arcticons You and future compatible packs use the same icon-theme contract.
     */
    fun changesWithMaterialYouColors(packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        val intent = iconPackQueryIntent(
            materialYouColorsOnly = true,
            packageName = packageName
        )
        return getResolves(intent).any { it.activityInfo.packageName == packageName }
    }

    override fun getAppFilterRawElements(iconPackName: String, applications: List<InstalledApplication>): List<RawElement> {
        val res = resourceResolver.getResources(iconPackName) ?: return emptyList()
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
        val res = resourceResolver.getResources(iconPackName) ?: return IconPackFallback()
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
        val drawables = getDrawableFromAppFilterElements(iconPackName, elements)
        return matchDrawablesToApplications(applications, drawables)
    }

    /**
     * Resolves only [application]'s drawable instead of decoding every matching entry in a pack.
     * Used by the per-app picker, which asks the same question once for every installed pack.
     */
    fun getDrawableFromAppFilterElements(
        iconPackName: String,
        application: InstalledApplication,
        elements: List<RawElement>
    ): ResourceDrawable? {
        val component = application.toComponentInfo()
        // Match the full-map path's duplicate handling: the last declaration wins.
        val item = lastAppFilterItem(elements, component) ?: return null
        return getResourceDrawableByName(iconPackName, item.drawableLink)
    }

    fun getResourceDrawableByName(iconPackName: String, drawableName: String): ResourceDrawable? {
        val res = resourceResolver.getResources(iconPackName) ?: return null
        val resourceId = runCatching {
            res.getIdentifierByName(drawableName, "drawable", iconPackName)
        }.getOrNull() ?: return null
        if (resourceId <= 0) return null
        val drawable = runCatching { resourceResolver.getDrawable(res, resourceId) }.getOrNull() ?: return null
        return ResourceDrawable(resourceId, drawable)
    }

    private fun getDrawableFromAppFilterElements(iconPackName: String, elements: List<RawElement>): Map<String, ResourceDrawable> {
        val map = mutableMapOf<String, ResourceDrawable>()
        val res = resourceResolver.getResources(iconPackName) ?: return map

        for (element in elements) {
            if (element is RawItem) {
                val resourceId = runCatching {
                    res.getIdentifierByName(element.drawableLink, "drawable", iconPackName)
                }.getOrNull() ?: continue

                if (resourceId > 0) {
                    val drawable = runCatching { resourceResolver.getDrawable(res, resourceId) }.getOrNull()
                    if (drawable != null)
                        map[element.component] = ResourceDrawable(resourceId, drawable)
                }
            }
        }

        return map
    }

    override fun getIconPackDrawableNames(iconPackName: String): List<String> {
        val res = resourceResolver.getResources(iconPackName) ?: return emptyList()
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
        val res = resourceResolver.getResources(iconPackName) ?: return emptyList()
        return resolveNamedDrawables(
            drawableNames,
            resolveId = { name -> res.getIdentifierByName(name, "drawable", iconPackName) },
            loadDrawable = { id -> resourceResolver.getDrawable(res, id) }
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

    private fun getIconPacks(
        intent: Intent,
        materialYouPacks: Set<String> = emptySet()
    ): List<IconPack> {
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
                    iconPacks.add(
                        IconPack(
                            packageName,
                            appName,
                            versionCode,
                            versionName,
                            iconID,
                            changesWithMaterialYouColors = packageName in materialYouPacks
                        )
                    )
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
        val componentSet = components.toHashSet()

        var type = xmlParser.eventType

        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                if (xmlParser.name == "item") {
                    val xmlDrawable = xmlParser.getAttributeValue(null, "drawable")
                    val xmlComponent = xmlParser.getAttributeValue(null, "component")

                    if (xmlComponent in componentSet && xmlDrawable != null) {
                        list.add(RawItem(xmlComponent, xmlDrawable))
                    }
                }

                if (xmlParser.name == "calendar") {
                    val xmlPrefix = xmlParser.getAttributeValue(null, "prefix")
                    val xmlComponent = xmlParser.getAttributeValue(null, "component")

                    if (xmlComponent in componentSet && xmlPrefix != null) {
                        list.add(RawCalendar(xmlComponent, xmlPrefix))
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
        return resourceResolver.getXml(res, packageName, "appfilter")
    }

    private fun getAssetAppfilter(res: Resources): XmlPullParser? {
        return resourceResolver.getAssetXml(res, "appfilter.xml")
    }

    private fun getDrawable(res: Resources, packageName: String): XmlPullParser? {
        val xmlParser = getResDrawable(res, packageName)

        if (xmlParser != null) return xmlParser
        return getAssetDrawable(res)
    }

    private fun getResDrawable(res: Resources, packageName: String): XmlPullParser? {
        return resourceResolver.getXml(res, packageName, "drawable")
    }

    private fun getAssetDrawable(res: Resources): XmlPullParser? {
        return resourceResolver.getAssetXml(res, "drawable.xml")
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

    fun getVersionCode(pack: PackageInfo): Long {
        // PackageInfoCompat handles the longVersionCode (API 28+) vs versionCode split.
        return PackageInfoCompat.getLongVersionCode(pack)
    }
}
