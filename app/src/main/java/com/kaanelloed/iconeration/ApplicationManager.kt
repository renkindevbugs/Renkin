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
import android.os.Build
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.UserManager
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import com.kaanelloed.iconeration.data.IconPack
import com.kaanelloed.iconeration.data.IconPackApplication
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class ApplicationManager(private val ctx: Context) {
    private val pm = ctx.packageManager

    fun getInstalledApps(): Array<PackageInfoStruct> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        return getApps(mainIntent)
    }

    fun getAllInstalledApps(): Array<PackageInfoStruct> {
        val userManager = ctx.getSystemService(Context.USER_SERVICE) as UserManager
        val apps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

        val packInfoStructs = mutableListOf<PackageInfoStruct>()

        for (user in userManager.userProfiles) {
            val usrApps = apps.getActivityList(null, user)

            if (usrApps.isNotEmpty()) {
                for (app in usrApps) {
                    val packInfo = PackageInfoStruct()
                    packInfo.appName = app.applicationInfo.loadLabel(pm).toString()
                    packInfo.packageName = app.componentName.packageName
                    packInfo.activityName = app.componentName.className
                    packInfo.icon = app.applicationInfo.loadIcon(pm)
                    packInfo.iconID = app.applicationInfo.icon
                    packInfo.source = PackageInfoStruct.PackageSource.Device

                    if (!packInfoStructs.contains(packInfo))
                        packInfoStructs.add(packInfo)
                }
            }
        }

        return packInfoStructs.toTypedArray()
    }

    fun getIconPacks(): List<IconPack> {
        val apps = getIconPackApps()
        val packs = mutableListOf<IconPack>()

        for (app in apps) {
            val pack = IconPack(app.packageName, app.appName, app.versionCode, app.versionName, app.iconID)
            packs.add(pack)
        }

        return packs.toList()
    }

    fun getIconPackApps(): Array<PackageInfoStruct> {
        return getApps(Intent("org.adw.launcher.THEMES", null))
    }

    fun getIconPackApplications(iconPackName: String): List<IconPackApplication> {
        val apps = getPackageApps(iconPackName)
        val packApps = mutableListOf<IconPackApplication>()

        for (app in apps) {
            val packApp = IconPackApplication(iconPackName, app.packageName, app.activityName, app.appName, app.iconID)
            packApps.add(packApp)
        }

        return packApps.toList()
    }

    private fun getPackageApps(packageName: String): Array<PackageInfoStruct> {
        val res = pm.getResourcesForApplication(packageName)
        val xmlParser = getAppfilter(res, packageName)

        if (xmlParser != null) {
            return getAppsFromAppfilter(res, xmlParser, packageName)
        }

        return emptyArray()
    }

    fun getMissingPackageApps(packageName: String, includeAvailable: Boolean = false): Array<PackageInfoStruct> {
        return if (includeAvailable)
            getPackageAppsWithMissing(packageName)
        else
            getMissingPackageAppsOnly(packageName)
    }

    private fun getMissingPackageAppsOnly(packageName: String): Array<PackageInfoStruct> {
        val missingApps = mutableListOf<PackageInfoStruct>()
        val packApps = getPackageApps(packageName)
        val installedApps = getAllInstalledApps()

        for (installedApp in installedApps) {
            if (!packApps.contains(installedApp)) {
                missingApps.add(installedApp)
            }
        }

        return missingApps.toTypedArray()
    }

    private fun getPackageAppsWithMissing(packageName: String): Array<PackageInfoStruct> {
        val missingApps = mutableListOf<PackageInfoStruct>()
        val packApps = getPackageApps(packageName)
        val installedApps = getAllInstalledApps()

        for (installedApp in installedApps) {
            if (!packApps.contains(installedApp)) {
                missingApps.add(installedApp)
            } else {
                val i = packApps.indexOf(installedApp)
                val packApp = packApps[i]

                val packInfo = PackageInfoStruct()
                packInfo.appName = installedApp.appName
                packInfo.packageName = installedApp.packageName
                packInfo.activityName = installedApp.activityName
                packInfo.icon = installedApp.icon
                packInfo.genIcon = packApp.icon.toBitmap()
                packInfo.source = PackageInfoStruct.PackageSource.IconPack

                missingApps.add(packInfo)
            }
        }

        return missingApps.toTypedArray()
    }

    private fun getApps(intent: Intent): Array<PackageInfoStruct> {
        val resolves = getResolves(intent)
        val packInfoStructs = mutableListOf<PackageInfoStruct>()

        for (pack in resolves) {
            //val res = pm.getResourcesForApplication(pack.activityInfo.applicationInfo)

            val packInfo = PackageInfoStruct()
            packInfo.appName = pack.activityInfo.applicationInfo.loadLabel(pm).toString()
            packInfo.packageName = pack.activityInfo.packageName
            packInfo.activityName = pack.activityInfo.name
            packInfo.icon = pack.activityInfo.applicationInfo.loadIcon(pm)
            packInfo.iconID = pack.activityInfo.applicationInfo.icon
            packInfo.source = PackageInfoStruct.PackageSource.Device

            val pack2 = getPackage(pack.activityInfo.packageName)!!
            packInfo.versionCode = getVersionCode(pack2)
            packInfo.versionName = pack2.versionName

            packInfoStructs.add(packInfo)
        }

        return packInfoStructs.toTypedArray()
    }

    private fun getAppsFromAppfilter(res: Resources, xmlParser: XmlPullParser, packageName: String): Array<PackageInfoStruct> {
        val packApps = mutableListOf<PackageInfoStruct>()
        var type = xmlParser.eventType

        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                if (xmlParser.name == "item") {
                    val iconName = xmlParser.getAttributeValue(null, "drawable")
                    val componentInfo = xmlParser.getAttributeValue(null, "component")

                    val components = ComponentInfo()
                    if (iconName != null && componentInfo != null && components.parse(componentInfo)) {
                        val iconId = getIdentifier(res, iconName, packageName)
                        val icon = getResIcon(res, iconId)

                        if (iconId > 0) {
                            val packInfo = PackageInfoStruct()
                            packInfo.appName = iconName
                            packInfo.packageName = components.packageName
                            packInfo.activityName = components.activityNane
                            packInfo.icon = icon!!
                            packInfo.iconID = iconId
                            packInfo.source = PackageInfoStruct.PackageSource.IconPack

                            if (!packApps.contains(packInfo))
                                packApps.add(packInfo)
                        }
                    }
                }
            }

            type = xmlParser.next()
        }

        return packApps.toTypedArray()
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

    @SuppressLint("DiscouragedApi")
    private fun getResAppfilter(res: Resources, packageName: String): XmlPullParser? {
        val id = res.getIdentifier("appfilter", "xml", packageName)
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

    private fun getResIcon(res: Resources, iconName: String, packageName: String): Drawable? {
        val id = getIdentifier(res, iconName, packageName)
        return getResIcon(res, id)
    }

    private fun getResIcon(res: Resources, resourceId: Int): Drawable? {
        if (resourceId > 0) return ResourcesCompat.getDrawable(res, resourceId, null)
        return null
    }

    @SuppressLint("DiscouragedApi")
    private fun getIdentifier(res: Resources, iconName: String, packageName: String): Int {
        return res.getIdentifier(iconName, "drawable", packageName)
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
        return try {
            res.getXml(resourceId)
        } catch (e: Resources.NotFoundException) {
            null
        }
    }

    @SuppressWarnings("deprecation")
    fun getVersionCode(pack: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
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
}