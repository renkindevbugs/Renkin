package com.kaanelloed.iconeration

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

class PackageInfoStruct: Comparable<PackageInfoStruct> {
    lateinit var appName: String
    lateinit var packageName: String
    lateinit var activityName: String
    lateinit var icon: Drawable
    lateinit var genIcon: Bitmap

    override fun equals(other: Any?): Boolean {
        if (other is PackageInfoStruct) {
            return packageName == other.packageName && activityName == other.activityName
        }

        return false
    }

    override fun compareTo(other: PackageInfoStruct): Int = when {
        this.appName != other.appName -> this.appName compareTo other.appName // compareTo() in the infix form
        else -> 0
    }
}