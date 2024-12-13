package com.kaanelloed.iconeration.packages

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.activity.ComponentActivity

class PermissionManager(val context: ComponentActivity) {
    @SuppressLint("InlinedApi")
    fun isPostNotificationEnabled(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("InlinedApi")
    fun askForPostNotification() {
        ActivityCompat.requestPermissions(context,
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            112);
    }
}