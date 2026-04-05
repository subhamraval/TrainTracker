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
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = TrackingPrefs.getConfig(this) ?: run {
                    stopSelf(); return START_NOT_STICKY
                }
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
                NotificationHelper.sendAlert(this, "Tracking Stopped", "Track Stopped Manually")
                shutdown()
            }
        }
        return START_STICKY
    }

    private fun startTrackingLoop(config: TrackingConfig) {
        loopJob?.cancel()
        loopJob = serviceScope.launch {
            checkAvailability(config)
            while (isActive && TrackingPrefs.isTracking(this@TrackingService)) {
                delay(CHECK_INTERVAL_MS)
                if (!isActive || !TrackingPrefs.isTracking(this@TrackingService)) break
                checkAvailability(config)
            }
        }
    }

    private suspend fun checkAvailability(config: TrackingConfig) {
        Log.d(TAG, "Checking train ${config.trainNo}")

        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        TrackingPrefs.saveLastCheckedTime(this, timeFormat.format(Date()))

        if (hasDeparturePassed(config)) {
            val lastStatus = TrackingPrefs.getLastStatus(this)
            if (lastStatus.startsWith("CURR_AVBL-")) {
                NotificationHelper.sendAlert(this, "No Current Available", "No Current Available")
            } else {
                NotificationHelper.sendAlert(this, "Tracking Ended",
                    "Train ${config.trainNo} departed. No CURR_AVBL found.")
            }
            TrackingPrefs.setTracking(this, false)
            withContext(Dispatchers.Main) { shutdown() }
            return
        }

        try {
            // Raw response fetch karo
            val rawBody = ApiClient.api.fetchRaw(
                trainNo                = config.trainNo,
                travelClass            = config.travelClass,
                quota                  = config.quota,
                sourceStationCode      = config.fromStation,
                destinationStationCode = config.toStation,
                dateOfJourney          = config.dateOfJourney
            ).string()

            Log.d(TAG, "Raw response: ${rawBody.take(200)}")

            // Parse karo
            val response = ApiClient.gson.fromJson(rawBody, AvailabilityResponse::class.java)
            val avlList = response?.data?.avlDayList

            if (avlList.isNullOrEmpty()) {
                Log.w(TAG, "Empty avlDayList. Error: ${response?.data?.errorMessage}")
                return
            }

            val targetDay = avlList.find { matchDates(it.availablityDate, config.dateOfJourney) }
            if (targetDay == null) {
                Log.w(TAG, "Date ${config.dateOfJourney} not found in response")
                return
            }

            processStatus(targetDay.availablityStatus)

        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        }
    }

    private fun processStatus(currentStatus: String) {
        val lastStatus = TrackingPrefs.getLastStatus(this)
        val isCurrAvbl = currentStatus.startsWith("CURR_AVBL-")
        val wasAvbl    = lastStatus.startsWith("CURR_AVBL-")

        if (isCurrAvbl) {
            val seatCount = extractCount(currentStatus)
            if (TrackingPrefs.isFirstCurrAvbl(this) || !wasAvbl) {
                NotificationHelper.sendAlert(this,
                    "Current Available Started!",
                    "Current Available Started $seatCount")
                TrackingPrefs.setFirstCurrAvbl(this, false)
            } else {
                val prevCount = extractCount(lastStatus)
                if (seatCount != prevCount) {
                    NotificationHelper.sendAlert(this,
                        "Seats Updated",
                        "Current Available $seatCount")
                }
            }
            TrackingPrefs.saveLastStatus(this, currentStatus)
        } else {
            if (wasAvbl) {
                NotificationHelper.sendAlert(this, "No Current Available", "No Current Available")
                TrackingPrefs.setTracking(this, false)
                TrackingPrefs.saveLastStatus(this, currentStatus)
                serviceScope.launch(Dispatchers.Main) { shutdown() }
            } else {
                TrackingPrefs.saveLastStatus(this, currentStatus)
                Log.d(TAG, "Status: $currentStatus — waiting for CURR_AVBL")
            }
        }
    }

    private fun extractCount(status: String): Int =
        status.removePrefix("CURR_AVBL-").trimStart('0').ifEmpty { "0" }.toIntOrNull() ?: 0

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
            val departure = sdf.parse("${config.dateOfJourney} ${config.departureTime}") ?: return false
            Date().after(departure)
        } catch (e: Exception) { false }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TrainTracker::WakeLock"
        ).also { it.acquire(12 * 60 * 60 * 1000L) }
    }

    private fun shutdown() {
        wakeLock?.let { if (it.isHeld) it.release() }
        loopJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
    }
}
