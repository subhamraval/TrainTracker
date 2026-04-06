package com.traintracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AlarmManager har 10 min mein yeh receiver ko call karega
 * Yeh TrackingService start karega ek check ke liye
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AlarmReceiver", "Alarm received — starting check")

        if (!TrackingPrefs.isTracking(context)) {
            Log.d("AlarmReceiver", "Tracking off hai — alarm ignore")
            return
        }

        // Service start karo ek check ke liye
        context.startForegroundService(
            Intent(context, TrackingService::class.java).apply {
                action = TrackingService.ACTION_CHECK
            }
        )
    }
}
