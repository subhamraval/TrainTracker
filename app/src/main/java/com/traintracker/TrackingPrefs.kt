package com.traintracker

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

object TrackingPrefs {
    private const val PREFS_NAME = "TrackingPrefs"
    private const val KEY_CONFIG            = "tracking_config"
    private const val KEY_IS_TRACKING       = "is_tracking"
    private const val KEY_LAST_STATUS       = "last_status"
    private const val KEY_IS_FIRST_CURR     = "is_first_curr_avbl"
    private const val KEY_LAST_CHECKED_TIME = "last_checked_time"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveConfig(context: Context, config: TrackingConfig) {
        prefs(context).edit()
            .putString(KEY_CONFIG, Gson().toJson(config))
            .commit()  // commit — guaranteed save
    }

    fun getConfig(context: Context): TrackingConfig? {
        val json = prefs(context).getString(KEY_CONFIG, null) ?: return null
        return try { Gson().fromJson(json, TrackingConfig::class.java) }
        catch (e: Exception) { null }
    }

    fun setTracking(context: Context, isTracking: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_TRACKING, isTracking).commit()
    }

    fun isTracking(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_TRACKING, false)

    fun saveLastStatus(context: Context, status: String) {
        prefs(context).edit().putString(KEY_LAST_STATUS, status).commit()
    }

    fun getLastStatus(context: Context): String =
        prefs(context).getString(KEY_LAST_STATUS, "") ?: ""

    fun setFirstCurrAvbl(context: Context, isFirst: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_FIRST_CURR, isFirst).commit()
    }

    fun isFirstCurrAvbl(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_FIRST_CURR, true)

    fun saveLastCheckedTime(context: Context, time: String) {
        prefs(context).edit().putString(KEY_LAST_CHECKED_TIME, time).commit()
    }

    fun getLastCheckedTime(context: Context): String =
        prefs(context).getString(KEY_LAST_CHECKED_TIME, "") ?: ""

    fun clearAll(context: Context) {
        prefs(context).edit().clear().commit()
    }
}
