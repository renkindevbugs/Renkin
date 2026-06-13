package dev.alembiconsProject.alembicons.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UpdateIconPackReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        val notificationManager = NotificationManager()
        notificationManager.stopNewApplicationNotification(context)
        notificationManager.startUpdatePackService(context, intent)
    }
}