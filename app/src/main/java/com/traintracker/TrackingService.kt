package com.traintracker

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class TrackingService : Service() {

    companion object {
        private const val TAG = "TrackingService"
        const val ACTION_START       = "ACTION_START"
        const val ACTION_STOP_MANUAL = "ACTION_STOP_MANUAL"

        // 10 minutes
        private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L

        fun startTracking(context: Context, config: TrackingConfig) {
            TrackingPrefs.saveConfig(context, config)
            TrackingPrefs.setTracking(context, true)
            TrackingPrefs.setFirstCurrAvbl(context, true)
            TrackingPrefs.saveLastStatus(context, "")
            TrackingPrefs.saveLastCheckedTime(context, "")

            context.startForegroundService(
                Intent(context, TrackingService::class.java).apply {
                    action = ACTION_START
                }
            )
        }

        fun stopManually(context: Context) {
            context.startService(
                Intent(context, TrackingService::class.java).apply {
                    action = ACTION_STOP_MANUAL
                }
            )
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null

    // WakeLock — phone sleep mein bhi check karta rahega
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            ACTION_START -> {
                val config = TrackingPrefs.getConfig(this) ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Foreground start karo (MUST be within 5 seconds)
                startForeground(
                    NotificationHelper.NOTIF_ID_FOREGROUND,
                    NotificationHelper.buildForegroundNotification(
                        this, config.trainNo, config.travelClass
                    )
                )

                acquireWakeLock()
                startTrackingLoop(config)
            }

            ACTION_STOP_MANUAL -> {
                TrackingPrefs.setTracking(this, false)
                NotificationHelper.sendAlert(
                    this,
                    "🛑 Tracking Stopped",
                    "Track Stopped Manually"
                )
                shutdown()
            }
        }

        // START_STICKY — agar system ne kill kiya toh Android khud restart karega service
        return START_STICKY
    }

    private fun startTrackingLoop(config: TrackingConfig) {
        loopJob?.cancel()
        loopJob = serviceScope.launch {
            // Pehli baar immediately check karo
            checkAvailability(config)

            // Phir har 10 minute mein
            while (isActive && TrackingPrefs.isTracking(this@TrackingService)) {
                delay(CHECK_INTERVAL_MS)
                if (!isActive || !TrackingPrefs.isTracking(this@TrackingService)) break
                checkAvailability(config)
            }
        }
    }

    private suspend fun checkAvailability(config: TrackingConfig) {
        Log.d(TAG, "Checking train ${config.trainNo} | class ${config.travelClass}")

        // Save last checked time
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        TrackingPrefs.saveLastCheckedTime(this, timeFormat.format(Date()))

        // Departure time nikal gayi? → stop
        if (hasDeparturePassed(config)) {
            val lastStatus = TrackingPrefs.getLastStatus(this)
            if (lastStatus.startsWith("CURR_AVBL-")) {
                NotificationHelper.sendAlert(
                    this,
                    "❌ No Current Available",
                    "No Current Available — Train ${config.trainNo} departed from ${config.fromStation}"
                )
            } else {
                NotificationHelper.sendAlert(
                    this,
                    "🚂 Tracking Ended",
                    "Train ${config.trainNo} has departed. No CURR_AVBL seat was found."
                )
            }
            TrackingPrefs.setTracking(this, false)
            withContext(Dispatchers.Main) { shutdown() }
            return
        }

        try {
            val response = ApiClient.api.fetchAvailability(
                trainNo           = config.trainNo,
                travelClass       = config.travelClass,
                quota             = config.quota,
                sourceStationCode = config.fromStation,
                destinationStationCode = config.toStation,
                dateOfJourney     = config.dateOfJourney
            )

            val avlList = response.data?.avlDayList
            if (avlList.isNullOrEmpty()) {
                Log.w(TAG, "Empty response. Error: ${response.data?.errorMessage}")
                return
            }

            // Find the entry for user's journey date
            val targetDay = avlList.find { matchDates(it.availablityDate, config.dateOfJourney) }
            if (targetDay == null) {
                Log.w(TAG, "Date ${config.dateOfJourney} not in response list")
                return
            }

            processStatus(targetDay.availablityStatus)

        } catch (e: Exception) {
            Log.e(TAG, "API call failed: ${e.message}")
            // Network error — quietly retry next interval
        }
    }

    private fun processStatus(currentStatus: String) {
        val lastStatus = TrackingPrefs.getLastStatus(this)
        val isCurrAvbl  = currentStatus.startsWith("CURR_AVBL-")
        val wasAvbl     = lastStatus.startsWith("CURR_AVBL-")

        if (isCurrAvbl) {
            // Seat count extract karo — "CURR_AVBL-0038" → "38"
            val seatCount = extractCount(currentStatus)

            if (TrackingPrefs.isFirstCurrAvbl(this) || !wasAvbl) {
                // Pehli baar CURR_AVBL mili!
                NotificationHelper.sendAlert(
                    this,
                    "🎉 Current Available Started!",
                    "Current Available Started $seatCount"
                )
                TrackingPrefs.setFirstCurrAvbl(this, false)
            } else {
                // Pehle bhi CURR_AVBL tha — count change hua?
                val prevCount = extractCount(lastStatus)
                if (seatCount != prevCount) {
                    NotificationHelper.sendAlert(
                        this,
                        "🚃 Seats Updated",
                        "Current Available $seatCount"
                    )
                }
                // Count same hai → koi notification nahi (spam avoid)
            }

            TrackingPrefs.saveLastStatus(this, currentStatus)

        } else {
            // CURR_AVBL nahi hai
            if (wasAvbl) {
                // Pehle tha, ab nahi → track band karo
                NotificationHelper.sendAlert(
                    this,
                    "❌ No Current Available",
                    "No Current Available"
                )
                TrackingPrefs.setTracking(this, false)
                TrackingPrefs.saveLastStatus(this, currentStatus)
                serviceScope.launch(Dispatchers.Main) { shutdown() }
            } else {
                // Abhi tak CURR_AVBL aaya hi nahi — silently track karte raho
                TrackingPrefs.saveLastStatus(this, currentStatus)
                Log.d(TAG, "Status: $currentStatus — waiting for CURR_AVBL...")
            }
        }
    }

    /**
     * "CURR_AVBL-0038" → 38
     * "CURR_AVBL-0008" → 8
     */
    private fun extractCount(status: String): Int {
        return status
            .removePrefix("CURR_AVBL-")
            .trimStart('0')
            .ifEmpty { "0" }
            .toIntOrNull() ?: 0
    }

    /**
     * API response mein date "26-3-2026" aati hai (no leading zero in month)
     * User input "26-03-2026" deta hai (with leading zero)
     * Dono ko compare karte hain integer form mein
     */
    private fun matchDates(availDate: String, targetDate: String): Boolean {
        val a = availDate.split("-")
        val b = targetDate.split("-")
        if (a.size < 3 || b.size < 3) return false
        return a[0].toIntOrNull() == b[0].toIntOrNull() &&
               a[1].toIntOrNull() == b[1].toIntOrNull() &&
               a[2] == b[2]
    }

    private fun hasDeparturePassed(config: TrackingConfig): Boolean {
        return try {
            val sdf = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
            val departure = sdf.parse("${config.dateOfJourney} ${config.departureTime}")
                ?: return false
            Date().after(departure)
        } catch (e: Exception) {
            false
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TrainTracker::TrackingWakeLock"
        ).also { it.acquire(12 * 60 * 60 * 1000L) } // max 12 hours
    }

    private fun shutdown() {
        wakeLock?.let { if (it.isHeld) it.release() }
        loopJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swipe karke band karo — service phir bhi chalta rahe
        // START_STICKY handle karega restart
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
    }
}
