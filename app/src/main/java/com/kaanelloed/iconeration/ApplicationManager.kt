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
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.UserManager
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
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
                    packInfo.source = PackageInfoStruct.PackageSource.Device

                    if (!packInfoStructs.contains(packInfo))
                        packInfoStructs.add(packInfo)
                }
            }
        }

        return packInfoStructs.toTypedArray()
    }

    fun getIconPackApps(): Array<PackageInfoStruct> {
        return getApps(Intent("org.adw.launcher.THEMES", null))
    }

    fun getPackageApps(packageName: String): Array<PackageInfoStruct> {
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
            packInfo.source = PackageInfoStruct.PackageSource.Device

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
                        val icon = getResIcon(res, iconName, packageName)

                        if (icon != null) {
                            val packInfo = PackageInfoStruct()
                            packInfo.appName = iconName
                            packInfo.packageName = components.packageName
                            packInfo.activityName = components.activityNane
                            packInfo.icon = icon
                            packInfo.source = PackageInfoStruct.PackageSource.IconPack

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

    @SuppressLint("DiscouragedApi")
    private fun getResIcon(res: Resources, iconName: String, packageName: String): Drawable? {
        val id = res.getIdentifier(iconName, "drawable", packageName)
        if (id > 0) return ResourcesCompat.getDrawable(res, id, null)

        return null
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