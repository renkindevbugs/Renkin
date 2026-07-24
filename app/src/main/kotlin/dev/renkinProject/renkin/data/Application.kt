package dev.renkinProject.renkin.data

data class IconPack(
    val packageName: String,
    val applicationName: String,
    val versionCode: Long,
    val versionName: String,
    val iconID: Int,
    val changesWithMaterialYouColors: Boolean = false
)

data class InstalledApplication(
    val packageName: String,
    val activityName: String,
    val iconID: Int
)
