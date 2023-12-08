package com.kaanelloed.iconeration

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.UserManager
import androidx.core.content.res.ResourcesCompat
import com.kaanelloed.iconeration.data.IconPack
import com.kaanelloed.iconeration.data.IconPackApplication
import com.kaanelloed.iconeration.data.InstalledApplication
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

                    val packInfo = PackageInfoStruct(
                        appName,
                        packageName,
                        activityName,
                        icon,
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

    fun getIconPackApplications(iconPackName: String): List<IconPackApplication> {
        val res = pm.getResourcesForApplication(iconPackName)
        val xmlParser = getAppfilter(res, iconPackName)

        if (xmlParser != null) {
            return getAppsFromAppFilter(res, xmlParser, iconPackName)
        }

        return emptyList()
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
            val versionName = pack.versionName

            val iconPack = IconPack(packageName, appName, versionCode, versionName, iconID)
            iconPacks.add(iconPack)
        }

        return iconPacks
    }

    private fun getAppsFromAppFilter(res: Resources, xmlParser: XmlPullParser, packageName: String): List<IconPackApplication> {
        val packApps = mutableListOf<IconPackApplication>()
        var type = xmlParser.eventType

        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                if (xmlParser.name == "item") {
                    val iconName = xmlParser.getAttributeValue(null, "drawable")
                    val componentInfo = xmlParser.getAttributeValue(null, "component")

                    val components = ComponentInfo()
                    if (iconName != null && componentInfo != null && components.parse(componentInfo)) {
                        val iconId = res.getIdentifierByName(iconName, "drawable", packageName)

                        if (iconId > 0) {
                            val appPackageName = components.packageName
                            val activityName = components.activityNane

                            val packApp = IconPackApplication(packageName, appPackageName, activityName, iconName, iconId)

                            if (!packApps.any { it.packageName == appPackageName && it.activityName == activityName})
                                packApps.add(packApp)
                        }
                    }
                }
            }

            type = xmlParser.next()
        }

        return packApps
    }

    fun checkAppFilter(xmlParser: XmlPullParser): Array<String> {
        val badlyFormattedComponents = mutableListOf<String>()
        var type = xmlParser.eventType

        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                if (xmlParser.name == "item") {
                    val iconName = xmlParser.getAttributeValue(null, "drawable")
                    val componentInfo = xmlParser.getAttributeValue(null, "component")

                    val components = ComponentInfo()
                    if (iconName == null || componentInfo == null || !components.parse(componentInfo)) {
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

    private fun getResolves(intent: Intent): List<ResolveInfo> {
        if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU)
            return pm.queryIntentActivities(intent, ResolveInfoFlags.of(0))
        return pm.queryIntentActivities(intent, 0)
    }

    private fun getAppfilter(res: Resources, packageName: String): XmlPullParser? {
        val xmlParser = getResAppfilter(res, packageName)

        if (xmlParser != null) return xmlParser
        return getAssetAppfilter(res)
    }

    private fun getResAppfilter(res: Resources, packageName: String): XmlPullParser? {
        val id = res.getIdentifierByName("appfilter", "xml", packageName)
        if (id > 0) return res.getXml(id)

        return null
    }

    private fun getAssetAppfilter(res: Resources): XmlPullParser? {
        val assets = res.assets.list("")

        if (assets != null && assets.contains("appfilter.xml")) {
            val xmlInStream = res.assets.open("appfilter.xml")
            val xmlParser = XmlPullParserFactory.newInstance().newPullParser()
            xmlParser.setInput(xmlInStream, "utf-8")

            return xmlParser
        }

        return null
    }

    fun getIconPackApplicationResources(packageName: String,
                                        iconPackApps: List<IconPackApplication>
    ): Map<IconPackApplication, Pair<Int, Drawable>> {
        val map = mutableMapOf<IconPackApplication, Pair<Int, Drawable>>()
        val res = pm.getResourcesForApplication(packageName)
        for (iconPackApp in iconPackApps) {
            map[iconPackApp] = Pair(iconPackApp.resourceID, getResIcon(res, iconPackApp.resourceID)!!)
        }

        return map
    }

    private fun getResIcon(res: Resources, iconName: String, packageName: String): Drawable? {
        val id = res.getIdentifierByName(iconName, "drawable", packageName)
        return getResIcon(res, id)
    }

    private fun getResIcon(res: Resources, resourceId: Int): Drawable? {
        if (resourceId > 0) return ResourcesCompat.getDrawable(res, resourceId, null)
        return null
    }

    fun getResIcon(packageName: String, resourceId: Int): Drawable? {
        val res = pm.getResourcesForApplication(packageName)
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
        val res = pm.getResourcesForApplication(packageName)
        return try {
            res.getResourceTypeName(resourceId)
        } catch (e: Resources.NotFoundException) {
            null
        }
    }

    fun getPackageResourceXml(packageName: String, resourceId: Int): XmlPullParser? {
        val res = pm.getResourcesForApplication(packageName)
        return res.getXmlOrNull(resourceId)
    }

    @Suppress("DEPRECATION")
    @SuppressWarnings("DEPRECATION")
    fun getVersionCode(pack: PackageInfo): Long {
        return if (VERSION.SDK_INT >= VERSION_CODES.P)
            pack.longVersionCode
        else
            pack.versionCode.toLong()
    }

    inner class ComponentInfo {
        private val componentPrefix = "ComponentInfo"
        lateinit var packageName: String
            private set
        lateinit var activityNane: String
            private set

        fun parse(text: String): Boolean {
            var newText = text

            if (!text.startsWith(componentPrefix, true))
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
            if (thirdSplit.count() < 2)
                return false

            packageName = thirdSplit[0]
            activityNane = thirdSplit[1]

            return true
        }
    }

    companion object {
        @SuppressLint("DiscouragedApi")
        fun Resources.getIdentifierByName(name: String, defType: String, defPackage: String): Int {
            return getIdentifier(name, defType, defPackage)
        }

        fun Resources.getXmlOrNull(resourceId: Int): XmlPullParser? {
            return try {
                this.getXml(resourceId)
            } catch (e: Resources.NotFoundException) {
                null
            }
        }
    }
}