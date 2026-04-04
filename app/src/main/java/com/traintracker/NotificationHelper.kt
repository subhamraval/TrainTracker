package com.traintracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object NotificationHelper {

    const val CHANNEL_TRACKING = "tracking_channel"
    const val CHANNEL_ALERTS   = "alerts_channel"
    const val NOTIF_ID_FOREGROUND = 1001

    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Silent ongoing notification — tracking active hai
        val trackingChannel = NotificationChannel(
            CHANNEL_TRACKING,
            "Train Tracking Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background tracking ka ongoing silent notification"
            setShowBadge(false)
        }

        // High priority — seat milne par loud notification
        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS,
            "Seat Availability Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Jab seat available ho tab loud notification"
            enableVibration(true)
            enableLights(true)
        }

        manager.createNotificationChannel(trackingChannel)
        manager.createNotificationChannel(alertChannel)
    }

    fun buildForegroundNotification(context: Context, trainNo: String, travelClass: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("🚂 Tracking Train $trainNo")
            .setContentText("$travelClass seat availability — checking every 10 min")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun sendAlert(context: Context, title: String, message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        // Unique ID — multiple alerts ek doosre ko overwrite nahi karenge
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
