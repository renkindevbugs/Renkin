package dev.renkinProject.renkin.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.renkinProject.renkin.MainActivity
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.packages.PackageVersion

/**
 * Builds and posts the app's notifications. Named to avoid shadowing the framework
 * [android.app.NotificationManager], which this class uses internally for channels.
 *
 * Only the icon-watch "icon available" notification remains — Renkin no longer notifies on
 * new app installs.
 */
class RenkinNotifications {
    // The app id is brand-new (dev.renkinProject.renkin), so the channel id could be renamed
    // without stranding an old channel on user devices.
    private val iconAvailableChannelId = "renkin_icon_available"

    // Watch notifications get a stable id per suggestion so they update/cancel cleanly
    private val iconAvailableBaseId = 1000
    // Bundle all icon-available notifications under one group + summary so several
    // firing at once collapse into a single entry instead of flooding the shade
    private val iconAvailableGroupKey = "dev.renkinProject.renkin.ICON_AVAILABLE"
    private val iconAvailableSummaryId = 999

    /** Posts (or updates) a notification that a watched pack has a new icon for an app. */
    fun postIconAvailable(
        context: Context,
        suggestionId: Long,
        appPackage: String,
        packPackages: List<String>,
        profileId: Long,
        profileName: String
    ) {
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
            putExtra(MainActivity.EXTRA_SUGGESTION_PROFILE_ID, profileId)
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
            // Which profile found it — shown in the notification header.
            .setSubText(profileName)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setGroup(iconAvailableGroupKey)
        if (largeIcon != null) builder.setLargeIcon(largeIcon)

        // The summary the system shows when several of these are collapsed together
        val summary = NotificationCompat.Builder(context, iconAvailableChannelId)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.iconAvailableSummaryTitle))
            .setContentText(context.getString(R.string.iconAvailableSummaryText))
            .setStyle(NotificationCompat.InboxStyle())
            .setGroup(iconAvailableGroupKey)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()

        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            notify(iconAvailableBaseId + suggestionId.toInt(), builder.build())
            notify(iconAvailableSummaryId, summary)
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
}
