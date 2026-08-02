package dev.renkinProject.renkin.packages

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity

/**
 * Single source of truth for "may Renkin post a notification right now".
 *
 * POST_NOTIFICATIONS only exists as a runtime permission from API 33. On API 23-32 the system
 * does not know it, so `checkSelfPermission` answers DENIED forever — gating on it there would
 * silently drop every icon-watch notification on older devices. Below 33 the user's per-app
 * notification toggle is the only real gate.
 */
@SuppressLint("InlinedApi")
fun canPostNotifications(context: Context): Boolean {
    return if (PackageVersion.is33OrMore()) {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

/**
 * The system screen where notifications can be switched back on. Per-app notification settings
 * exist from API 26; older devices only have the generic app-details page.
 */
fun notificationSettingsIntent(context: Context): Intent {
    return if (PackageVersion.is26OrMore()) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
    }
}

class PermissionManager(val context: ComponentActivity) {
    fun isPostNotificationEnabled(): Boolean = canPostNotifications(context)

    /**
     * True when a runtime prompt can still be shown. Below API 33 there is no such permission,
     * so re-enabling notifications is a system-settings trip instead — callers must offer that
     * route rather than silently doing nothing.
     */
    fun canAskForPostNotification(): Boolean = PackageVersion.is33OrMore()

    /** No-op below API 33: asking for a permission the system does not know returns a denial. */
    @SuppressLint("InlinedApi")
    fun askForPostNotification() {
        if (!canAskForPostNotification()) return
        ActivityCompat.requestPermissions(context,
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            112);
    }
}
