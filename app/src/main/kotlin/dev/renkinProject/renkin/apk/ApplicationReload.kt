package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.packages.PackageInfoStruct

/** Keys whose in-memory state differs from the last saved/built profile. */
internal fun unsavedApplicationKeys(
    applications: List<PackageInfoStruct>,
    builtKeys: Set<String>,
    updatedKeys: Set<String>
): Set<String> {
    val liveKeys = applications.mapTo(mutableSetOf()) { it.key }
    val result = updatedKeys.filterTo(mutableSetOf()) { it in liveKeys }
    applications.filter { it.isRefreshMade }.mapTo(result) { it.key }
    applications.filter { it.key in builtKeys && it.createdIcon == null }.mapTo(result) { it.key }
    return result
}

/**
 * Applies only genuine session edits over a freshly discovered/reloaded application list.
 * System-owned metadata (label, launcher icon/id and original name) comes from [reloaded]; icon
 * creation and calendar state comes from [current]. Matching is exact package/activity identity.
 */
internal fun mergeApplicationReload(
    current: List<PackageInfoStruct>,
    reloaded: List<PackageInfoStruct>,
    preserveKeys: Set<String>
): List<PackageInfoStruct> {
    val currentByKey = current.associateBy { it.key }
    return reloaded.map { fresh ->
        val session = currentByKey[fresh.key]
        if (fresh.key !in preserveKeys || session == null) fresh else PackageInfoStruct(
            appName = fresh.appName,
            packageName = fresh.packageName,
            activityName = fresh.activityName,
            icon = fresh.icon,
            iconID = fresh.iconID,
            createdIcon = session.createdIcon,
            internalVersion = maxOf(fresh.internalVersion, session.internalVersion) + 1,
            calendarEnabled = session.calendarEnabled,
            calendarPrefix = session.calendarPrefix,
            calendarPackName = session.calendarPackName,
            sourcePackName = session.sourcePackName,
            originalName = fresh.originalName,
            isFallback = session.isFallback,
            isRefreshMade = session.isRefreshMade,
            isCustom = session.isCustom,
            isLegacy = session.isLegacy,
            baseIcon = session.baseIcon,
            sourceUrl = session.sourceUrl
        )
    }
}
