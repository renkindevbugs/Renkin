package dev.renkinProject.renkin.packages

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Process
import androidx.annotation.WorkerThread

/** One distinct launcher icon an app declares; [component] is empty for the row's own icon. */
internal class AlternateApplicationIcon(
    val component: String,
    val label: String?,
    // One stable instance per icon keeps the preview cache key stable across recompositions.
    val source: ApplicationIconSource.PackageActivity
)

internal data class LauncherAliasEntry(val activity: String, val iconResourceId: Int, val label: String?)

/**
 * Apps such as Reddit ship alternate icons as disabled launcher activity-aliases. Aliases that
 * share an icon resource collapse into one entry; the row's own icon leads.
 */
internal fun groupAlternateIcons(rowActivity: String, entries: List<LauncherAliasEntry>): List<LauncherAliasEntry> {
    val groups = entries.groupBy { it.iconResourceId }.values
        .map { group -> group.firstOrNull { it.activity == rowActivity } ?: group.first() }
    if (groups.size <= 1) return emptyList()
    val own = groups.firstOrNull { it.activity == rowActivity } ?: return emptyList()
    return listOf(own) + (groups - own)
}

@WorkerThread
internal fun Context.alternateApplicationIcons(app: PackageInfoStruct): List<AlternateApplicationIcon> {
    val configuration = when (val source = app.iconSource) {
        // PackageManager answers for this user only; another profile's aliases are out of reach.
        is ApplicationIconSource.Installed ->
            if (source.user != Process.myUserHandle()) return emptyList() else source.configuration()
        is ApplicationIconSource.PackageActivity -> source.configuration()
        ApplicationIconSource.Unavailable -> return emptyList()
        else -> Configuration(resources.configuration)
    }
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
    @Suppress("DEPRECATION")
    val resolved = runCatching { packageManager.queryIntentActivities(intent, DISABLED_COMPONENT_FLAGS) }
        .getOrDefault(emptyList())
    val appLabel = app.originalName
    val entries = resolved.mapNotNull { it.activityInfo }
        .filter { it.packageName == app.packageName }
        .distinctBy { it.name }
        .map { activity ->
            val label = runCatching { activity.loadLabel(packageManager).toString() }.getOrNull()
            LauncherAliasEntry(activity.name, activity.iconResource, label?.takeIf { it != appLabel && it != app.appName })
        }
    return groupAlternateIcons(app.activityName, entries).map { entry ->
        AlternateApplicationIcon(
            component = if (entry.activity == app.activityName) "" else entry.activity,
            label = entry.label,
            source = ApplicationIconSource.PackageActivity(ComponentName(app.packageName, entry.activity), configuration)
        )
    }
}
