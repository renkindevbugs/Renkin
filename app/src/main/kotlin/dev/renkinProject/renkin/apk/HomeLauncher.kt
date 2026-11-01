package dev.renkinProject.renkin.apk

import android.content.Intent
import android.content.pm.PackageManager
import dev.renkinProject.renkin.util.Log

/** The default home app, named in install failure reports. */
data class HomeLauncher(val packageName: String, val label: String)

/** The default home app, or null when none is set (the platform chooser would resolve instead). */
@Suppress("DEPRECATION")
internal fun resolveHomeLauncher(packageManager: PackageManager): HomeLauncher? = try {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        ?.takeUnless { it.packageName == "android" }
        ?.let { activity ->
            val label = activity.applicationInfo?.loadLabel(packageManager)?.toString()
                ?.takeIf { it.isNotBlank() } ?: activity.packageName
            HomeLauncher(activity.packageName, label)
        }
} catch (error: Exception) {
    Log.error("HomeLauncher", "Could not resolve the default home app", error)
    null
}
