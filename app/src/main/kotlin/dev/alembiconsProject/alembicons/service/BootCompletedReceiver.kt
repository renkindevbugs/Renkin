package dev.alembiconsProject.alembicons.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        // Re-register the icon-watch package monitor after a reboot.
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startService(Intent(context, PackageAddedService::class.java))
        }
    }
}
