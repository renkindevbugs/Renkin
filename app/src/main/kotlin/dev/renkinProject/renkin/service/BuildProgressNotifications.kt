package dev.renkinProject.renkin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.renkinProject.renkin.BuildInstallState
import dev.renkinProject.renkin.MainActivity
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.packages.canPostNotifications

internal object BuildProgressNotifications {
    const val ONGOING_ID = 901
    private const val RESULT_ID = 902
    private const val CHANNEL = "renkin_pack_build"

    private fun builder(context: Context): NotificationCompat.Builder {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.buildNotificationChannel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun open(context: Context, install: Boolean = false): PendingIntent = PendingIntent.getActivity(
        context, if (install) 902 else 901,
        Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_BUILD
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_INSTALL_READY_PACK, install)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun ongoing(context: Context, state: BuildInstallState): Notification {
        val building = state as? BuildInstallState.Building
        val progress = building?.progress
        return builder(context)
            .setContentTitle(context.getString(when (state) {
                is BuildInstallState.Installing -> R.string.buildInstalling
                is BuildInstallState.Done -> R.string.buildNotificationFinishing
                else -> R.string.buildNotificationTitle
            }))
            .setContentText(progress?.let { context.getString(R.string.buildNotificationCount, it.first, it.second) }
                ?: (state as? BuildInstallState.Done)?.packLabel
                ?: building?.step?.takeIf { it.isNotBlank() } ?: context.getString(R.string.buildNotificationPreparing))
            .setOngoing(true)
            .setProgress(progress?.second?.coerceAtLeast(1) ?: 0, progress?.first ?: 0, progress == null)
            .build()
    }

    fun ready(context: Context, state: BuildInstallState) {
        val request = when (state) {
            is BuildInstallState.Ready -> state.request
            is BuildInstallState.ChoosingInstaller -> state.request
            is BuildInstallState.Conflict -> state.request
            else -> return
        }
        post(context, builder(context)
            .setContentTitle(context.getString(R.string.buildReady))
            .setContentText(request.pack.packLabel)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.buildReadyInstall), open(context, install = true))
            .build())
    }

    fun result(context: Context, message: String) {
        post(context, builder(context).setContentTitle(context.getString(R.string.iconPack))
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true).build())
    }

    private fun post(context: Context, notification: Notification) {
        if (!canPostNotifications(context)) return
        try { NotificationManagerCompat.from(context).notify(RESULT_ID, notification) } catch (_: SecurityException) { }
    }

    fun clearResult(context: Context) { NotificationManagerCompat.from(context).cancel(RESULT_ID) }
}
