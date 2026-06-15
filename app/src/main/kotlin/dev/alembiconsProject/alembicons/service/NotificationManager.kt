package dev.alembiconsProject.alembicons.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.alembiconsProject.alembicons.MainActivity
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.drawable.toSafeBitmapOrNull
import dev.alembiconsProject.alembicons.packages.PackageVersion

class NotificationManager {
    private val newApplicationChannelId = "alembicons_package_added"
    private val updatePackChannelId = "alembicons_update_pack"
    private val iconAvailableChannelId = "alembicons_icon_available"

    private val newApplicationNotificationId = 0
    private val updatePackNotificationId = 1
    // Watch notifications get a stable id per suggestion so they update/cancel cleanly
    private val iconAvailableBaseId = 1000

    private val newApplicationTag = "dev.alembiconsProject.alembicons"

    private val newApplicationAction = "new_package"

    fun startNewApplicationNotification(context: Context, data: Uri?) {
        createNewApplicationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val updateIntent = Intent(newApplicationAction, data, context, UpdateIconPackReceiver()::class.java)

        val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val updatePendingIntent = PendingIntent.getBroadcast(context, 0, updateIntent,
            PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, newApplicationChannelId)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.newApplicationNotificationText))
            .setStyle(
                NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.newApplicationNotificationLongText)))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .addAction(R.mipmap.ic_launcher_monochrome, context.getString(R.string.update), updatePendingIntent)

        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            notify(newApplicationTag, newApplicationNotificationId, builder.build())
        }
    }

    fun startUpdatePackNotification(service: Service) {
        createUpdatePackChannel(service)

        val notification = NotificationCompat.Builder(service, updatePackChannelId)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(service.getString(R.string.app_name))
            .setContentText(service.getString(R.string.updatePackNotificationText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(0, 0, true)
            .build()

        ServiceCompat.startForeground(service, updatePackNotificationId, notification, getForegroundType())
    }

    fun stopNewApplicationNotification(context: Context) {
        with(NotificationManagerCompat.from(context)) {
            cancel(newApplicationTag, newApplicationNotificationId)
        }
    }

    fun startNewApplicationService(context: Context) {
        //TODO: Handle wakelock with WorkManager ?
        context.startService(Intent(context, PackageAddedService::class.java))
    }

    fun startUpdatePackService(context: Context, intent: Intent?) {
        val updateIntent = Intent(newApplicationAction, intent?.data, context, UpdateIconPackService::class.java)
        ContextCompat.startForegroundService(context, updateIntent)
    }

    /** Posts (or updates) a notification that a watched pack has a new icon for an app. */
    fun postIconAvailable(context: Context, suggestionId: Long, appPackage: String, packPackages: List<String>) {
        createIconAvailableChannel(context)
        val pm = context.packageManager

        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(appPackage, 0)).toString()
        } catch (_: Exception) {
            appPackage
        }

        val text = if (packPackages.size > 1) {
            context.getString(R.string.iconAvailableTextMulti, packPackages.size)
        } else {
            val packName = packPackages.firstOrNull()?.let {
                try { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() } catch (_: Exception) { it }
            } ?: ""
            context.getString(R.string.iconAvailableTextSingle, packName)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_SUGGESTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SUGGESTION_ID, suggestionId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, suggestionId.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val largeIcon = try {
            pm.getApplicationIcon(appPackage).toSafeBitmapOrNull()
        } catch (_: Exception) {
            null
        }

        val builder = NotificationCompat.Builder(context, iconAvailableChannelId)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.iconAvailableTitle, appName))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        if (largeIcon != null) builder.setLargeIcon(largeIcon)

        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            notify(iconAvailableBaseId + suggestionId.toInt(), builder.build())
        }
    }

    private fun createIconAvailableChannel(context: Context) {
        if (PackageVersion.is26OrMore()) {
            val name = context.getString(R.string.iconAvailableChannelName)
            val descriptionText = context.getString(R.string.iconAvailableChannelDescription)
            val channel = NotificationChannel(iconAvailableChannelId, name, NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = descriptionText

            val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNewApplicationChannel(context: Context) {
        if (PackageVersion.is26OrMore()) {
            val name = context.getString(R.string.newApplicationChannelName)
            val descriptionText = context.getString(R.string.newApplicationChannelDescription)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(newApplicationChannelId, name, importance)
            channel.description = descriptionText

            val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createUpdatePackChannel(context: Context) {
        if (PackageVersion.is26OrMore()) {
            val name = context.getString(R.string.updatePackChannelName)
            val descriptionText = context.getString(R.string.updatePackChannelDescription)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(updatePackChannelId, name, importance)
            channel.description = descriptionText

            val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun getForegroundType(): Int {
        return if (PackageVersion.is29OrMore()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
    }
}