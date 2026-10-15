package dev.renkinProject.renkin.apk

import android.content.Intent
import android.content.pm.PackageManager
import dev.renkinProject.renkin.util.Log

enum class LauncherIconPackSupport { PICK_IN_SETTINGS, THEME_PARK, NONE, UNKNOWN }

data class HomeLauncher(val packageName: String, val label: String) {
    val iconPackSupport: LauncherIconPackSupport get() = launcherIconPackSupport(packageName)
}

// Only launchers whose icon pack handling is documented or verified; everything else stays UNKNOWN.
private val pickInSettings = setOf(
    "app.lawnchair", "app.lawnchair.play", "de.mm20.launcher2.release", "com.teslacoilsw.launcher",
    "bitpit.launcher", "ginlemon.flowerfree", "ginlemon.flowerpro"
)

internal fun launcherIconPackSupport(packageName: String): LauncherIconPackSupport = when (packageName) {
    in pickInSettings -> LauncherIconPackSupport.PICK_IN_SETTINGS
    "com.sec.android.app.launcher" -> LauncherIconPackSupport.THEME_PARK
    "com.google.android.apps.nexuslauncher" -> LauncherIconPackSupport.NONE
    else -> LauncherIconPackSupport.UNKNOWN
}

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
