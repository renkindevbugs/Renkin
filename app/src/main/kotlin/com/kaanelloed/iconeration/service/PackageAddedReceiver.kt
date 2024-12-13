package com.kaanelloed.iconeration.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.Preferences
import com.kaanelloed.iconeration.data.AutomaticallyUpdateKey
import com.kaanelloed.iconeration.data.getBooleanValue
import com.kaanelloed.iconeration.dataStore
import com.kaanelloed.iconeration.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PackageAddedReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        //Ignore updated application
        if (intent?.extras?.getBoolean(Intent.EXTRA_REPLACING, false) == true) {
            return
        }

        if (intent?.action == Intent.ACTION_PACKAGE_ADDED) {
            Log.debug("Alembicons", intent.data.toString() + " added")
            CoroutineScope(Dispatchers.Default).launch {
                handleNewApplication(context, intent)
            }
        }

        if (intent?.action == Intent.ACTION_PACKAGE_REMOVED) {
            Log.debug("Alembicons", intent.data.toString() + " removed")
        }
    }

    private suspend fun handleNewApplication(context: Context, intent: Intent) {
        context.dataStore.data.collect {
            handleNewApplication(context, intent, it)
        }
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