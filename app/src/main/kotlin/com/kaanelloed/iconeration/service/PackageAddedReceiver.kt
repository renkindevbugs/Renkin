package com.kaanelloed.iconeration.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kaanelloed.iconeration.MainActivity
import com.kaanelloed.iconeration.R
import com.kaanelloed.iconeration.packages.PackageVersion
import com.kaanelloed.iconeration.util.Log

class PackageAddedReceiver: BroadcastReceiver() {
    private val channelId = "alembicons_package_added"

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        //Ignore updated application
        if (intent?.extras?.getBoolean(Intent.EXTRA_REPLACING, false) == true) {
            return
        }

        if (intent?.action == Intent.ACTION_PACKAGE_ADDED) {
            Log.debug("Alembicons", intent.data.toString() + " added")
            buildNotification(context)
        }

        if (intent?.action == Intent.ACTION_PACKAGE_REMOVED) {
            Log.debug("Alembicons", intent.data.toString() + " removed")
        }
    }

    private fun buildNotification(context: Context) {
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notifText))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.notifLongText)))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            notify("com.kaanelloed.iconeration", 0, builder.build())
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (PackageVersion.is26OrMore()) {
            val name = context.getString(R.string.channelName)
            val descriptionText = context.getString(R.string.channelDesc)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance)
            channel.description = descriptionText

            val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}