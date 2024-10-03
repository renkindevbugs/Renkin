package com.kaanelloed.iconeration.packages

import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.activity.ComponentActivity

class PermissionManager(val context: ComponentActivity) {
    fun isPostNotificationEnabled(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            "android.permission.POST_NOTIFICATIONS" //Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun askForPostNotification() {
        ActivityCompat.requestPermissions(context,
            arrayOf("android.permission.POST_NOTIFICATIONS"),
            112);
    }
}