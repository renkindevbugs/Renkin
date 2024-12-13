package com.kaanelloed.iconeration.service

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder

class PackageAddedService: Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onCreate() {
        val intent = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }

        intent.priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1

        registerReceiver(PackageAddedReceiver(), intent)
    }
}