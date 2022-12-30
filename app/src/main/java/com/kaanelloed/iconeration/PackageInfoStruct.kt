package com.kaanelloed.iconeration

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

class PackageInfoStruct {
    lateinit var appName: String
    lateinit var packageName: String
    lateinit var activityName: String
    lateinit var icon: Drawable
    lateinit var genIcon: Bitmap
}