package dev.alembiconsProject.alembicons.packages

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.UserManager
import dev.alembiconsProject.alembicons.constants.SuppressDeprecation
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.InstalledApplication
import dev.alembiconsProject.alembicons.data.RawCalendar
import dev.alembiconsProject.alembicons.data.RawDynamicClock
import dev.alembiconsProject.alembicons.data.RawElement
import dev.alembiconsProject.alembicons.data.RawItem
import dev.alembiconsProject.alembicons.data.toComponentInfo
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable
import dev.alembiconsProject.alembicons.drawable.hasValidDimensions
import dev.alembiconsProject.alembicons.extension.getDrawableOrNull
import dev.alembiconsProject.alembicons.extension.getIdentifierByName
import dev.alembiconsProject.alembicons.extension.getXmlOrNull
import dev.alembiconsProject.alembicons.extension.toDrawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class ApplicationManager(private val ctx: Context) {
    private val pm = ctx.packageManager

    fun getAllInstalledApplications(): List<InstalledApplication> {
        val apps = getAllInstalledApps()
        val packs = mutableListOf<InstalledApplication>()

        for (app in apps) {
            val pack = InstalledApplication(app.packageName, app.activityName, app.iconID)
            packs.add(pack)
        }

        return packs.toList()
    }

    fun getAllInstalledApps(): Array<PackageInfoStruct> {
        val userManager = ctx.getSystemService(Context.USER_SERVICE) as UserManager
        val apps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

        val packInfoStructs = mutableListOf<PackageInfoStruct>()

        for (user in userManager.userProfiles) {
            val usrApps = apps.getActivityList(null, user)

            if (usrApps.isNotEmpty()) {
                for (app in usrApps) {
                    val appName = app.applicationInfo.loadLabel(pm).toString()
                    val packageName = app.componentName.packageName
                    val activityName = app.componentName.className
                    val icon = app.applicationInfo.loadIcon(pm)
                    val iconID = app.applicationInfo.icon

                    val icon2 = if (!icon.hasValidDimensions()) {
                        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).toDrawable(ctx.resources)
                    } else
                        icon

                    val packInfo = PackageInfoStruct(
                        appName,
                        packageName,
                        activityName,
                        icon2,
                        iconID
                    )

                    if (!packInfoStructs.contains(packInfo))
                        packInfoStructs.add(packInfo)
                }
            }
        }

        return packInfoStructs.toTypedArray()
    }

    fun getIconPacks(): List<IconPack> {
        return getIconPacks(Intent("org.adw.launcher.THEMES", null))
    }

    fun getAppFilterRawElements(iconPackName: String, applications: List<InstalledApplication>): List<RawElement> {
        val res = getResources(iconPackName) ?: return emptyList()
        val xmlParser = getAppfilter(res, iconPackName)

        val components = applications.map { it.toComponentInfo() }

        if (xmlParser != null) {
            return getAppFilterRawElements(xmlParser, components)
        }

        return emptyList()
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
                val resourceId = res.getIdentifierByName(element.drawableLink, "drawable", iconPackName)

                if (resourceId > 0) {
                    val drawable = getResIcon(res, resourceId)!!
                    map[element.component] = ResourceDrawable(resourceId, drawable)
                }
            }
        }

        return map
    }

    fun getIconPackDrawableNames(iconPackName: String): List<String> {
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

        return list
    }

    fun getIconPackDrawableIds(iconPackName: String, drawableNames: List<String>): List<Int> {
        val list = mutableListOf<Int>()
        val res = getResources(iconPackName) ?: return list

        for (name in drawableNames) {
            val resourceId = res.getIdentifierByName(name, "drawable", iconPackName)

            if (resourceId > 0) {
                list.add(resourceId)
            }
        }

        return list
    }

    fun getIconPackDrawables(iconPackName: String, drawableIds: List<Int>): List<ResourceDrawable> {
        val list = mutableListOf<ResourceDrawable>()
        val res = getResources(iconPackName) ?: return list

        for (id in drawableIds) {
            val drawable = getResIcon(res, id)!!
            list.add(ResourceDrawable(id, drawable))
        }

        return list
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

    fun getCalendarFromAppFilterElements(iconPackName: String, elements: List<RawElement>): Map<String, Drawable> {
        val map = mutableMapOf<String, Drawable>()
        val res = getResources(iconPackName) ?: return map

        val calendarIcons = elements.filterIsInstance<RawCalendar>()
        for (calendar in calendarIcons) {
            for (i in 1 .. 31) {
                val resource = getResIcon(res, calendar.prefix + i, iconPackName)

                if (resource != null) {
                    map[calendar.prefix + i] = resource
                }
            }
        }

        return map
    }

    private fun getIconPacks(intent: Intent): List<IconPack> {
        val resolves = getResolves(intent)
        val iconPacks = mutableListOf<IconPack>()

        for (resolve in resolves) {
            val appName = resolve.activityInfo.applicationInfo.loadLabel(pm).toString()
            val packageName = resolve.activityInfo.packageName
            val iconID = resolve.activityInfo.applicationInfo.icon

            val pack = getPackage(resolve.activityInfo.packageName)!!
            val versionCode = getVersionCode(pack)
            val versionName = pack.versionName!!

            val iconPack = IconPack(packageName, appName, versionCode, versionName, iconID)
            iconPacks.add(iconPack)
        }

        return iconPacks
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
        if (dev.alembiconsProject.alembicons.packages.PackageVersion.is33OrMore())
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

    fun getResources(packageName: String): Resources? {
        return try {
            return pm.getResourcesForApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
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

    fun <T> changeManifestEnabledState(cls: Class<T>, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        val componentName = ComponentName(ctx, cls)
        pm.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP)
    }

    @Suppress(SuppressDeprecation)
    fun getVersionCode(pack: PackageInfo): Long {
        return if (dev.alembiconsProject.alembicons.packages.PackageVersion.is28OrMore())
            pack.longVersionCode
        else
            pack.versionCode.toLong()
    }
}