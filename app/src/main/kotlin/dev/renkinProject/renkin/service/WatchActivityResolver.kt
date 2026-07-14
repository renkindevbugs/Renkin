package dev.renkinProject.renkin.service

import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.watch.AppComponent

internal data class ResolvedWatchApp(
    val application: InstalledApplication,
    val componentChanged: Boolean
)

/** Keeps an exact launcher component, or chooses the only available replacement. */
internal fun resolveWatchApp(
    stored: AppComponent,
    packageActivities: List<InstalledApplication>
): ResolvedWatchApp? {
    val exact = packageActivities.firstOrNull { it.activityName == stored.activityName }
    if (exact != null) return ResolvedWatchApp(exact, componentChanged = false)

    return packageActivities.singleOrNull()?.let {
        ResolvedWatchApp(it, componentChanged = true)
    }
}
