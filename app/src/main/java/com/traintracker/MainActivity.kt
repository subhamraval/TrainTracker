package com.traintracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.traintracker.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedDate: String = ""
    private var autoFetchedDepartureTime: String = ""

    companion object {
        private const val PREF_BATTERY_ASKED = "battery_opt_asked"
        private const val PREF_NAME = "AppPrefs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        NotificationHelper.createChannels(this)
        requestNotificationPermission()
        askBatteryOptimizationIfNeeded()
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryOptimizationIfNeeded() {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val alreadyAsked = prefs.getBoolean(PREF_BATTERY_ASKED, false)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
        if (!isIgnoring && !alreadyAsked) {
            prefs.edit().putBoolean(PREF_BATTERY_ASKED, true).apply()
            AlertDialog.Builder(this)
                .setTitle("Battery Permission Zaroori Hai!")
                .setMessage("Background tracking ke liye Battery Optimization OFF karo.\n\nPlease Allow karo.")
                .setPositiveButton("Allow") { _, _ ->
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
                .setNegativeButton("Baad Mein") { d, _ -> d.dismiss() }
                .setCancelable(false)
                .show()
        }
    }

    private fun setupUI() {
        setupClassDropdown()
        setupQuotaDropdown()
        setupDatePicker()
        setupButtons()
    }

    private fun setupClassDropdown() {
        val classes = listOf("SL", "3A", "2A", "1A", "CC", "2S", "EC", "FC")
        binding.actvClass.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, classes))
        binding.actvClass.setText("SL", false)
    }

    private fun setupQuotaDropdown() {
        val quotas = listOf("GN", "TQ", "LD", "HP", "SS", "YU")
        binding.actvQuota.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, quotas))
        binding.actvQuota.setText("GN", false)
    }

    private fun setupDatePicker() {
        binding.etDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(this, { _, year, month, day ->
                selectedDate = String.format("%02d-%02d-%04d", day, month + 1, year)
                binding.etDate.setText(selectedDate)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                .apply { datePicker.minDate = System.currentTimeMillis() - 1000 }
                .show()
        }
    }

    private fun setupButtons() {
        binding.btnTrack.setOnClickListener { if (validateForm()) startTracking() }
        binding.btnStop.setOnClickListener {
            TrackingService.stopManually(this)
            TrackingPrefs.setTracking(this, false)
            refreshUI()
        }
        binding.btnTestNow.setOnClickListener { testNow() }
    }

    private fun testNow() {
        val trainNo     = binding.etTrainNo.text.toString().trim()
        val fromStation = binding.etFromStation.text.toString().trim().uppercase()
        val toStation   = binding.etToStation.text.toString().trim().uppercase()
        val travelClass = binding.actvClass.text.toString()
        val quota       = binding.actvQuota.text.toString()

        if (trainNo.length < 4) { binding.etTrainNo.error = "Train number daalo"; return }
        if (fromStation.length < 2) { binding.etFromStation.error = "From station daalo"; return }
        if (toStation.length < 2) { binding.etToStation.error = "To station daalo"; return }
        if (selectedDate.isEmpty()) { Toast.makeText(this, "Date select karo", Toast.LENGTH_SHORT).show(); return }

        binding.btnTestNow.isEnabled = false
        binding.tvApiResult.visibility = View.VISIBLE
        binding.tvApiResult.text = "Testing..."
        binding.tvApiResult.setTextColor(getColor(android.R.color.darker_gray))

        lifecycleScope.launch {
            val result = StringBuilder()

            // ── 1. Availability ───────────────────────────
            try {
                val raw = withContext(Dispatchers.IO) {
                    ApiClient.api.fetchRaw(
                        trainNo = trainNo, travelClass = travelClass,
                        quota = quota, sourceStationCode = fromStation,
                        destinationStationCode = toStation,
                        dateOfJourney = selectedDate
                    ).string()
                }
                val parsed  = ApiClient.gson.fromJson(raw, AvailabilityResponse::class.java)
                val avlList = parsed?.data?.avlDayList
                val target  = avlList?.find { day -> matchDates(day.availablityDate, selectedDate) }
                if (target != null) {
                    result.appendLine("✅ AVAILABILITY OK")
                    result.appendLine("Train: ${parsed?.data?.trainName}")
                    result.appendLine("Status: ${target.availablityStatus}")
                } else {
                    result.appendLine("⚠️ Availability: ${parsed?.data?.errorMessage ?: "data nahi mila"}")
                }
            } catch (e: Exception) {
                result.appendLine("❌ Availability Error: ${e.message}")
            }

            result.appendLine("")
            result.appendLine("Departure fetch ho raha hai...")
            binding.tvApiResult.text = result.toString()

            // ── 2. Departure Time ─────────────────────────
            val (depTime, debugLog) = withContext(Dispatchers.IO) {
                ApiClient.getDepartureTime(trainNo, fromStation)
            }

            result.appendLine("=== Debug ===")
            result.appendLine(debugLog)

            if (!depTime.isNullOrEmpty()) {
                autoFetchedDepartureTime = depTime
                result.appendLine("✅ DEPARTURE: $depTime")
                binding.tvDepartureStatus.visibility = View.VISIBLE
                binding.tvDepartureStatus.text = "✅ Departure from $fromStation: $depTime"
                binding.tvDepartureStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                binding.layoutManualTime.visibility = View.GONE
            } else {
                autoFetchedDepartureTime = ""
                result.appendLine("⚠️ Auto-fetch failed — manual daalo")
                showManualTimeInput("Auto-fetch failed")
            }

            binding.tvApiResult.text = result.toString()
            binding.tvApiResult.setTextColor(getColor(android.R.color.holo_blue_dark))
            binding.btnTestNow.isEnabled = true
        }
    }

    private fun showManualTimeInput(reason: String) {
        binding.tvDepartureStatus.visibility = View.VISIBLE
        binding.tvDepartureStatus.text = "⚠️ $reason — manually daalo"
        binding.tvDepartureStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
        binding.layoutManualTime.visibility = View.VISIBLE
    }

    private fun matchDates(availDate: String, targetDate: String): Boolean {
        val a = availDate.split("-")
        val b = targetDate.split("-")
        if (a.size < 3 || b.size < 3) return false
        return a[0].toIntOrNull() == b[0].toIntOrNull() &&
               a[1].toIntOrNull() == b[1].toIntOrNull() &&
               a[2] == b[2]
    }

    private fun getFinalDepartureTime(): String {
        return if (autoFetchedDepartureTime.isNotEmpty()) autoFetchedDepartureTime
        else binding.etDepartureTime.text.toString().trim()
    }

    private fun validateForm(): Boolean {
        val trainNo = binding.etTrainNo.text.toString().trim()
        val from    = binding.etFromStation.text.toString().trim()
        val to      = binding.etToStation.text.toString().trim()
        val depTime = getFinalDepartureTime()

        if (trainNo.length < 4) { binding.etTrainNo.error = "Valid train number daalo"; binding.etTrainNo.requestFocus(); return false }
        if (from.length < 2) { binding.etFromStation.error = "Station code daalo"; binding.etFromStation.requestFocus(); return false }
        if (to.length < 2) { binding.etToStation.error = "Station code daalo"; binding.etToStation.requestFocus(); return false }
        if (selectedDate.isEmpty()) { Toast.makeText(this, "Journey date select karo", Toast.LENGTH_SHORT).show(); return false }
        if (!depTime.matches(Regex("\\d{1,2}:\\d{2}"))) {
            showManualTimeInput("Departure time daalo")
            return false
        }
        return true
    }

    private fun startTracking() {
        val config = TrackingConfig(
            trainNo       = binding.etTrainNo.text.toString().trim(),
            fromStation   = binding.etFromStation.text.toString().trim().uppercase(),
            toStation     = binding.etToStation.text.toString().trim().uppercase(),
            dateOfJourney = selectedDate,
            travelClass   = binding.actvClass.text.toString(),
            quota         = binding.actvQuota.text.toString(),
            departureTime = getFinalDepartureTime()
        )
        TrackingService.startTracking(this, config)
        refreshUI()
        Toast.makeText(this, "Tracking shuru!", Toast.LENGTH_LONG).show()
    }

    private fun refreshUI() {
        val isTracking  = TrackingPrefs.isTracking(this)
        val config      = TrackingPrefs.getConfig(this)
        val lastStatus  = TrackingPrefs.getLastStatus(this)
        val lastChecked = TrackingPrefs.getLastCheckedTime(this)

        if (isTracking && config != null) {
            binding.btnTrack.visibility     = View.GONE
            binding.btnStop.visibility      = View.VISIBLE
            binding.formCard.visibility     = View.GONE
            binding.trackingCard.visibility = View.VISIBLE
            binding.tvApiResult.visibility  = View.GONE
            binding.tvTrackingInfo.text =
                "Train: ${config.trainNo}  |  ${config.travelClass}  |  ${config.quota}\n" +
                "${config.fromStation} → ${config.toStation}\n" +
                "Date: ${config.dateOfJourney}  |  Departs: ${config.departureTime}"
            val emoji = when {
                lastStatus.startsWith("CURR_AVBL") -> "🟢"
                lastStatus.startsWith("AVAILABLE") -> "🔵"
                lastStatus == "REGRET"             -> "🔴"
                else                               -> "🟡"
            }
            binding.tvCurrentStatus.text = if (lastStatus.isEmpty()) "$emoji Pehla check ho raha hai..." else "$emoji $lastStatus"
            binding.tvLastChecked.text   = if (lastChecked.isEmpty()) "Abhi check ho raha hai..." else "Last: $lastChecked | Next: ~10 min"
        } else {
            binding.btnTrack.visibility     = View.VISIBLE
            binding.btnStop.visibility      = View.GONE
            binding.formCard.visibility     = View.VISIBLE
            binding.trackingCard.visibility = View.GONE
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }
}
