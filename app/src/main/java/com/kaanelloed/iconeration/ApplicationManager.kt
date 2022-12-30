package com.kaanelloed.iconeration

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import androidx.core.content.res.ResourcesCompat
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class ApplicationManager(private val pm: PackageManager) {
    fun getInstalledApps(): Array<PackageInfoStruct> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)

        return getApps(mainIntent)
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

    fun getMissingPackageApps(packageName: String): Array<PackageInfoStruct> {
        val missingApps = mutableListOf<PackageInfoStruct>()
        val packApps = getPackageApps(packageName)
        val installedApps = getInstalledApps()

        for (installedApp in installedApps) {
            if (!packApps.contains(installedApp)) {
                missingApps.add(installedApp)
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
                    if (components.parse(componentInfo)) {
                        val icon = getResIcon(res, iconName, packageName)

                        if (icon != null) {
                            val packInfo = PackageInfoStruct()
                            packInfo.appName = iconName
                            packInfo.packageName = components.packageName
                            packInfo.activityName = components.activityNane
                            packInfo.icon = icon!!

                            packApps.add(packInfo)
                        }
                    }
                }
            }

            type = xmlParser.next()
        }

        return packApps.toTypedArray()
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
        val id = res.getIdentifier(iconName, "drawable", packageName)
        if (id > 0) return ResourcesCompat.getDrawable(res, id, null)

        return null;
    }

    inner class ComponentInfo() {
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