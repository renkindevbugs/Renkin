package com.kaanelloed.iconeration

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

class ApplicationManager {
    fun getInstalledApps(pm: PackageManager, includeSystemPackages: Boolean): Array<PackageInfoStruct> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)

        val resolves = pm.queryIntentActivities(mainIntent, 0)
        val packInfoStructs = mutableListOf<PackageInfoStruct>()

        for (pack in resolves) {
            //val res = pm.getResourcesForApplication(pack.activityInfo.applicationInfo)

            var packInfo = PackageInfoStruct()
            packInfo.appName = pack.activityInfo.applicationInfo.loadLabel(pm).toString()
            packInfo.packageName = pack.activityInfo.packageName
            packInfo.activityName = pack.activityInfo.name
            packInfo.versionName = ""
            packInfo.versionCode = 0
            packInfo.icon = pack.activityInfo.applicationInfo.loadIcon(pm)

            packInfoStructs.add(packInfo)
        }

        return packInfoStructs.toTypedArray()
    }
}

class PackageInfoStruct {
    lateinit var appName: String
    lateinit var packageName: String
    lateinit var activityName: String
    lateinit var versionName: String
    var versionCode: Int = 0
    lateinit var icon: Drawable
}