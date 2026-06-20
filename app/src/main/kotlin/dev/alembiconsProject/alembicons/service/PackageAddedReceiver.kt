package dev.alembiconsProject.alembicons.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.Preferences
import dev.alembiconsProject.alembicons.data.AutomaticallyUpdateKey
import dev.alembiconsProject.alembicons.data.getBooleanValue
import dev.alembiconsProject.alembicons.dataStore
import dev.alembiconsProject.alembicons.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PackageAddedReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        // A pack (or any app) was updated — let the watcher see if a watched pack
        // changed an icon. Handled before the EXTRA_REPLACING guard below (which exists
        // to suppress the new-app notification on updates). The check is version-gated.
        if (intent?.action == Intent.ACTION_PACKAGE_REPLACED) {
            Log.debug("Alembicons", intent.data.toString() + " replaced")
            WatchWorker.runNow(context)
            return
        }

        //Ignore updated application
        if (intent?.extras?.getBoolean(Intent.EXTRA_REPLACING, false) == true) {
            return
        }

        if (intent?.action == Intent.ACTION_PACKAGE_ADDED) {
            Log.debug("Alembicons", intent.data.toString() + " added")
            // goAsync keeps the process alive past onReceive while we read prefs and
            // act; finish() is called once the (now finite) work completes.
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    handleNewApplication(context, intent)
                } finally {
                    pendingResult.finish()
                }
            }
        }

        if (intent?.action == Intent.ACTION_PACKAGE_REMOVED) {
            Log.debug("Alembicons", intent.data.toString() + " removed")
        }
    }

    private suspend fun handleNewApplication(context: Context, intent: Intent) {
        // Read the current prefs once instead of collecting forever (which never
        // completed and leaked the coroutine).
        val prefs = context.dataStore.data.first()
        handleNewApplication(context, intent, prefs)
    }

    private fun handleNewApplication(context: Context, intent: Intent, prefs: Preferences) {
        val notificationManager = NotificationManager()

        if (prefs.getBooleanValue(AutomaticallyUpdateKey)) {
            notificationManager.startUpdatePackService(context, intent)
        } else {
            notificationManager.startNewApplicationNotification(context, intent.data)
        }
    }
}