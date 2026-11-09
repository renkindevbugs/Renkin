package dev.renkinProject.renkin.data.watch

enum class WatchReadFailureReason { PACK_UNREADABLE, MISSING_DRAWABLE, ICON_UNREADABLE }

data class WatchReadIssue(
    val packPackage: String,
    val reason: WatchReadFailureReason,
    val appPackage: String? = null,
    val activityName: String? = null,
    val drawableName: String? = null,
    val packName: String = packPackage,
    val appName: String? = null
)
