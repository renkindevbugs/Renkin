package dev.alembiconsProject.alembicons.service

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.datastore.preferences.core.Preferences
import dev.alembiconsProject.alembicons.apk.ApplicationProvider
import dev.alembiconsProject.alembicons.dataStore
import dev.alembiconsProject.alembicons.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UpdateIconPackService: Service() {
    private val appProvider = ApplicationProvider(this)

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager().startUpdatePackNotification(this)

        CoroutineScope(Dispatchers.Default).launch {
            appProvider.initialize()
            updateIconPack(intent?.data)
        }

        return super.onStartCommand(intent, flags, startId)
    }

    private suspend fun updateIconPack(data: Uri?) {
        if (data == null) return

        this@UpdateIconPackService.dataStore.data.collect {
            updateIconPack(data.schemeSpecificPart, it)
        }
    }

    private suspend fun updateIconPack(packageName: String, prefs: Preferences) {
        val app = appProvider.applicationList.find { it.packageName == packageName }

        if (app == null)
            return

        appProvider.retrieveOtherIcons(prefs)
        appProvider.refreshIcon(app, prefs)

        val iconPack = appProvider.buildAndSignIconPack(prefs) {}

        //TODO: Update notification
        appProvider.installIconPack(iconPack)

        Log.debug("Alembicons", "Alchemicon Pack updated")

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}