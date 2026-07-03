package dev.renkinProject.renkin.ui

import dev.renkinProject.renkin.packages.PackageInfoStruct

/**
 * The shared app search + filter + sort pipeline used by both the home app list and the watch
 * rule editor. Kept generic over the element type ([T]) via [selector] because the home list
 * carries each app's original index (to edit it by position) while the watch editor uses the
 * bare [PackageInfoStruct] — only the wrapper differs, the filtering rules are identical.
 *
 * Order of operations: text match (app name or original English name), then the mutually
 * exclusive Fallback / No-icon filter, then the sort. [installTimes] backs the install-date
 * sort and may be empty until it has been looked up off the main thread.
 */
fun <T> List<T>.sortedFilteredApps(
    query: String,
    filterNoIcon: Boolean,
    filterFallback: Boolean,
    sortOrder: AppSortOrder,
    installTimes: Map<String, Long>,
    selector: (T) -> PackageInfoStruct
): List<T> {
    val trimmed = query.trim()
    var seq = asSequence()
    if (trimmed.isNotEmpty()) seq = seq.filter {
        val app = selector(it)
        app.appName.contains(trimmed, ignoreCase = true) ||
            app.originalName.contains(trimmed, ignoreCase = true)
    }
    seq = when {
        filterFallback -> seq.filter { selector(it).isFallback }
        filterNoIcon -> seq.filter { selector(it).createdIcon == null }
        else -> seq
    }
    return when (sortOrder) {
        AppSortOrder.NAME -> seq.sortedBy { selector(it).appName.lowercase() }
        AppSortOrder.INSTALL_DATE -> seq.sortedByDescending { installTimes[selector(it).packageName] ?: 0L }
    }.toList()
}
