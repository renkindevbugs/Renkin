package com.kaanelloed.iconeration

import android.content.Intent
import android.content.pm.PackageManager

class ApplicationManager {
    fun getInstalledApps(pm: PackageManager): Array<PackageInfoStruct> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)

        val resolves = pm.queryIntentActivities(mainIntent, 0)
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
}