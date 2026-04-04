package com.traintracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Phone restart hone ke baad:
 * Agar tracking active thi → automatically restart ho jayegi
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.d("BootReceiver", "Boot received — checking if tracking was active")

            if (TrackingPrefs.isTracking(context)) {
                val config = TrackingPrefs.getConfig(context)
                if (config != null) {
                    Log.d("BootReceiver", "Restarting tracking for train ${config.trainNo}")
                    TrackingService.startTracking(context, config)
                }
            }
        }
    }
}
