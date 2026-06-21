package dev.alembiconsProject.alembicons.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.alembiconsProject.alembicons.util.Log

/**
 * Registered by [PackageAddedService] while icon-watch is active. Its only job is the
 * event-driven icon-watch fast path: when an installed pack is updated (PACKAGE_REPLACED),
 * run an immediate watch check. New-app installs are intentionally ignored — Renkin no
 * longer notifies on those.
 */
class PackageAddedReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        if (intent?.action == Intent.ACTION_PACKAGE_REPLACED) {
            Log.debug("Alembicons", intent.data.toString() + " replaced")
            WatchWorker.runNow(context)
        }
    }
}
