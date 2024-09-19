package com.kaanelloed.iconeration.data

data class IconPack(
    val packageName: String,
    val applicationName: String,
    val versionCode: Long,
    val versionName: String,
    val iconID: Int
)

data class InstalledApplication(
    val packageName: String,
    val activityName: String,
    val iconID: Int
)