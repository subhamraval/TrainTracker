package com.traintracker

import android.app.AlarmManager
import android.app.PendingIntent
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
        const val ACTION_CHECK       = "ACTION_CHECK"

        // UI refresh ke liye broadcast action
        const val ACTION_STATUS_UPDATED = "com.traintracker.STATUS_UPDATED"

        private const val INTERVAL_MS = 10 * 60 * 1000L

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

        fun scheduleNextAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = getPendingIntent(context)
            val triggerTime = System.currentTimeMillis() + INTERVAL_MS
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                )
                Log.d(TAG, "Next alarm scheduled in 10 min")
            } catch (e: Exception) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        }

        fun cancelAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(getPendingIntent(context))
            Log.d(TAG, "Alarm cancelled")
        }

        private fun getPendingIntent(context: Context): PendingIntent {
            return PendingIntent.getBroadcast(
                context, 0,
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
                doCheck(config)
            }

            ACTION_CHECK -> {
                val config = TrackingPrefs.getConfig(this) ?: run {
                    stopSelf(); return START_NOT_STICKY
                }
                if (!TrackingPrefs.isTracking(this)) {
                    stopSelf(); return START_NOT_STICKY
                }
                startForeground(
                    NotificationHelper.NOTIF_ID_FOREGROUND,
                    NotificationHelper.buildForegroundNotification(
                        this, config.trainNo, config.travelClass
                    )
                )
                acquireWakeLock()
                doCheck(config)
            }

            ACTION_STOP_MANUAL -> {
                TrackingPrefs.setTracking(this, false)
                cancelAlarm(this)
                NotificationHelper.sendAlert(this, "Tracking Stopped", "Track Stopped Manually")
                sendBroadcast(Intent(ACTION_STATUS_UPDATED))
                shutdown()
            }
        }
        return START_NOT_STICKY
    }

    private fun doCheck(config: TrackingConfig) {
        serviceScope.launch {
            try {
                Log.d(TAG, "Checking train ${config.trainNo}...")

                val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
                TrackingPrefs.saveLastCheckedTime(
                    this@TrackingService,
                    timeFormat.format(Date())
                )

                // Departure time nikal gayi?
                if (hasDeparturePassed(config)) {
                    val lastStatus = TrackingPrefs.getLastStatus(this@TrackingService)
                    if (lastStatus.startsWith("CURR_AVBL-")) {
                        NotificationHelper.sendAlert(
                            this@TrackingService,
                            "No Current Available",
                            "No Current Available — Train departed"
                        )
                    } else {
                        NotificationHelper.sendAlert(
                            this@TrackingService,
                            "Tracking Ended",
                            "Train ${config.trainNo} departed. No CURR_AVBL found."
                        )
                    }
                    TrackingPrefs.setTracking(this@TrackingService, false)
                    cancelAlarm(this@TrackingService)
                    sendBroadcast(Intent(ACTION_STATUS_UPDATED))
                    shutdown()
                    return@launch
                }

                // API call
                val rawBody = ApiClient.api.fetchRaw(
                    trainNo                = config.trainNo,
                    travelClass            = config.travelClass,
                    quota                  = config.quota,
                    sourceStationCode      = config.fromStation,
                    destinationStationCode = config.toStation,
                    dateOfJourney          = config.dateOfJourney
                ).string()

                Log.d(TAG, "Response: ${rawBody.take(150)}")

                val response = ApiClient.gson.fromJson(rawBody, AvailabilityResponse::class.java)
                val avlList  = response?.data?.avlDayList

                if (avlList.isNullOrEmpty()) {
                    Log.w(TAG, "Empty list: ${response?.data?.errorMessage}")
                    scheduleNextAlarm(this@TrackingService)
                    sendBroadcast(Intent(ACTION_STATUS_UPDATED))
                    shutdown()
                    return@launch
                }

                val targetDay = avlList.find {
                    matchDates(it.availablityDate, config.dateOfJourney)
                }

                if (targetDay == null) {
                    Log.w(TAG, "Date not found in response")
                    scheduleNextAlarm(this@TrackingService)
                    sendBroadcast(Intent(ACTION_STATUS_UPDATED))
                    shutdown()
                    return@launch
                }

                Log.d(TAG, "Status found: ${targetDay.availablityStatus}")
                processStatus(targetDay.availablityStatus)

            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
                if (TrackingPrefs.isTracking(this@TrackingService)) {
                    scheduleNextAlarm(this@TrackingService)
                }
                sendBroadcast(Intent(ACTION_STATUS_UPDATED))
                shutdown()
            }
        }
    }

    private fun processStatus(currentStatus: String) {
        val lastStatus = TrackingPrefs.getLastStatus(this)
        val isCurrAvbl = currentStatus.startsWith("CURR_AVBL-")
        val wasAvbl    = lastStatus.startsWith("CURR_AVBL-")

        Log.d(TAG, "processStatus: current=$currentStatus last=$lastStatus")

        if (isCurrAvbl) {
            val seatCount = extractCount(currentStatus)

            when {
                // Pehli baar CURR_AVBL
                TrackingPrefs.isFirstCurrAvbl(this) || !wasAvbl -> {
                    Log.d(TAG, "First CURR_AVBL detected! seats=$seatCount")
                    NotificationHelper.sendAlert(
                        this,
                        "Current Available Started!",
                        "Current Available Started $seatCount"
                    )
                    TrackingPrefs.setFirstCurrAvbl(this, false)
                }
                // Count badla
                else -> {
                    val prevCount = extractCount(lastStatus)
                    Log.d(TAG, "CURR_AVBL update: prev=$prevCount current=$seatCount")
                    if (seatCount != prevCount) {
                        NotificationHelper.sendAlert(
                            this,
                            "Seats Updated",
                            "Current Available $seatCount"
                        )
                    } else {
                        Log.d(TAG, "Count same ($seatCount) — no notification")
                    }
                }
            }

            // Save karo PEHLE schedule se
            TrackingPrefs.saveLastStatus(this, currentStatus)
            sendBroadcast(Intent(ACTION_STATUS_UPDATED))
            scheduleNextAlarm(this)
            shutdown()

        } else {
            if (wasAvbl) {
                // Tha, ab nahi — stop
                Log.d(TAG, "CURR_AVBL gone — stopping")
                NotificationHelper.sendAlert(this, "No Current Available", "No Current Available")
                TrackingPrefs.setTracking(this, false)
                TrackingPrefs.saveLastStatus(this, currentStatus)
                cancelAlarm(this)
                sendBroadcast(Intent(ACTION_STATUS_UPDATED))
                shutdown()
            } else {
                // Abhi tak nahi aaya — wait
                Log.d(TAG, "Status: $currentStatus — waiting for CURR_AVBL")
                TrackingPrefs.saveLastStatus(this, currentStatus)
                sendBroadcast(Intent(ACTION_STATUS_UPDATED))
                scheduleNextAlarm(this)
                shutdown()
            }
        }
    }

    private fun extractCount(status: String): Int =
        status.removePrefix("CURR_AVBL-")
            .trimStart('0')
            .ifEmpty { "0" }
            .toIntOrNull() ?: 0

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
        } catch (e: Exception) { false }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TrainTracker::WakeLock"
        ).also { it.acquire(5 * 60 * 1000L) }
    }

    private fun shutdown() {
        serviceScope.launch {
            delay(300)
            wakeLock?.let { if (it.isHeld) it.release() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
    }
}
