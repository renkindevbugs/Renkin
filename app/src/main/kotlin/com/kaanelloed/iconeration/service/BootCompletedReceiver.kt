package com.kaanelloed.iconeration.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            startPackageAddedService(context)
        }
    }

    private fun startPackageAddedService(context: Context) {
        //TODO: Handle wakelock, WorkManager ?
        context.startService(Intent(context, PackageAddedService::class.java))
    }
}