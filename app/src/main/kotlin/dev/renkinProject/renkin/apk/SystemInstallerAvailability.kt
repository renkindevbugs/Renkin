package dev.renkinProject.renkin.apk

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException

/** A discovery heuristic only: the platform supplies the actual confirmation intent later. */
@Suppress("DEPRECATION")
internal fun hasSystemInstallActivity(
    packageManager: PackageManager,
    apk: Uri,
    includeSessionConfirmation: Boolean = true
): Boolean {
    val intents = buildList {
        if (includeSessionConfirmation) add(Intent("android.content.pm.action.CONFIRM_INSTALL"))
        add(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(apk, APK_MIME_TYPE))
    }
    return try {
        intents.any { intent ->
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).any candidate@ { resolved ->
                val activity = resolved.activityInfo ?: return@candidate false
                val application = activity.applicationInfo ?: return@candidate false
                activity.enabled && activity.exported && application.enabled &&
                    application.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.error("ApkInstaller", "Could not discover an enabled system installer", error)
        false
    }
}
