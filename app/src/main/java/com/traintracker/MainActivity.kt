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
    private var selectedDate: String = ""   // "dd-MM-yyyy"
    private var fetchedDepartureTime: String = ""  // auto-fetched from API

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

    // ─────────────────────────────────────────
    // BATTERY OPTIMIZATION — first launch par
    // ─────────────────────────────────────────
    @SuppressLint("BatteryLife")
    private fun askBatteryOptimizationIfNeeded() {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val alreadyAsked = prefs.getBoolean(PREF_BATTERY_ASKED, false)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)

        if (!isIgnoring && !alreadyAsked) {
            prefs.edit().putBoolean(PREF_BATTERY_ASKED, true).apply()
            AlertDialog.Builder(this)
                .setTitle("⚡ Battery Permission Zaroori Hai!")
                .setMessage(
                    "Background mein tracking karne ke liye Battery Optimization OFF karna padega.\n\n" +
                    "Bina iske Android app ko kill kar dega aur tracking ruk jayegi.\n\n" +
                    "Please 'Allow' karo."
                )
                .setPositiveButton("Allow ✅") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                }
                .setNegativeButton("Baad Mein") { d, _ ->
                    d.dismiss()
                    Toast.makeText(this,
                        "⚠️ Battery permission nahi di — tracking kill ho sakti hai",
                        Toast.LENGTH_LONG).show()
                }
                .setCancelable(false)
                .show()
        }
    }

    // ─────────────────────────────────────────
    // UI SETUP
    // ─────────────────────────────────────────
    private fun setupUI() {
        setupClassDropdown()
        setupQuotaDropdown()
        setupDatePicker()
        setupScheduleFetch()
        setupButtons()
    }

    private fun setupClassDropdown() {
        val classes = listOf("SL", "3A", "2A", "1A", "CC", "2S", "EC", "FC")
        binding.actvClass.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, classes)
        )
        binding.actvClass.setText("SL", false)
    }

    private fun setupQuotaDropdown() {
        val quotas = listOf("GN", "TQ", "LD", "HP", "SS", "YU")
        binding.actvQuota.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, quotas)
        )
        binding.actvQuota.setText("GN", false)
    }

    private fun setupDatePicker() {
        binding.etDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    selectedDate = String.format("%02d-%02d-%04d", day, month + 1, year)
                    binding.etDate.setText(selectedDate)
                    // Date select hone par — agar trainNo + fromStation pehle se bhare hain toh fetch karo
                    tryAutoFetchSchedule()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).apply {
                datePicker.minDate = System.currentTimeMillis() - 1000
            }.show()
        }
    }

    // ─────────────────────────────────────────
    // AUTO FETCH DEPARTURE TIME
    // ─────────────────────────────────────────
    private fun setupScheduleFetch() {
        // Teeno fill hone ke baad hi fetch karo: trainNo + fromStation + date
        binding.etFromStation.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) tryAutoFetchSchedule()
        }
        binding.etTrainNo.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) tryAutoFetchSchedule()
        }
        // Date select hone par bhi trigger hoga (setupDatePicker mein already call hai)
    }

    private fun tryAutoFetchSchedule() {
        val trainNo     = binding.etTrainNo.text.toString().trim()
        val fromStation = binding.etFromStation.text.toString().trim().uppercase()

        when {
            trainNo.length < 4 || fromStation.length < 2 -> {
                // Abhi complete nahi hua — wait karo
            }
            selectedDate.isEmpty() -> {
                // Train no + from station ready hain, sirf date ka wait
                binding.tvDepartureStatus.visibility = View.VISIBLE
                binding.tvDepartureStatus.text = "Pehle date select karo — phir departure time auto-fetch hoga"
                binding.tvDepartureStatus.setTextColor(getColor(android.R.color.darker_gray))
            }
            else -> {
                // Teeno ready hain — fetch karo!
                fetchDepartureTime(trainNo, fromStation)
            }
        }
    }

    private fun fetchDepartureTime(trainNo: String, fromStation: String) {
        // Show loading state
        binding.tvDepartureStatus.visibility = View.VISIBLE
        binding.tvDepartureStatus.text = "⏳ Fetching schedule..."
        fetchedDepartureTime = ""

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.api.getTrainSchedule(trainNo)
                }

                val stations = response.body?.StationList
                if (stations.isNullOrEmpty()) {
                    showScheduleFallback("Schedule nahi mili — please manually enter karo")
                    return@launch
                }

                // From station match karo
                val match = stations.find {
                    it.StationCode?.equals(fromStation, ignoreCase = true) == true
                }

                if (match == null) {
                    showScheduleFallback("$fromStation station schedule mein nahi mila")
                    return@launch
                }

                val depTime = match.DepartureTime ?: match.ArrivalTime
                if (depTime.isNullOrEmpty() || depTime == "--" || depTime == "00:00") {
                    showScheduleFallback("Departure time unavailable")
                    return@launch
                }

                // Success!
                fetchedDepartureTime = depTime
                binding.tvDepartureStatus.text = "✅ Departure from $fromStation: $depTime"
                binding.tvDepartureStatus.setTextColor(getColor(android.R.color.holo_green_dark))

            } catch (e: Exception) {
                // API fail hoi — fallback to manual entry
                showScheduleFallback("Auto-fetch failed — please manually enter karo")
            }
        }
    }

    private fun showScheduleFallback(msg: String) {
        fetchedDepartureTime = ""
        binding.tvDepartureStatus.text = "⚠️ $msg"
        binding.tvDepartureStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
        binding.layoutManualTime.visibility = View.VISIBLE
    }

    // ─────────────────────────────────────────
    // BUTTONS
    // ─────────────────────────────────────────
    private fun setupButtons() {
        binding.btnTrack.setOnClickListener {
            if (validateForm()) startTracking()
        }
        binding.btnStop.setOnClickListener {
            TrackingService.stopManually(this)
            TrackingPrefs.setTracking(this, false)
            refreshUI()
        }
    }

    // ─────────────────────────────────────────
    // VALIDATION
    // ─────────────────────────────────────────
    private fun validateForm(): Boolean {
        val trainNo = binding.etTrainNo.text.toString().trim()
        val from    = binding.etFromStation.text.toString().trim()
        val to      = binding.etToStation.text.toString().trim()

        if (trainNo.length < 4) {
            binding.etTrainNo.error = "Valid train number daalo"
            binding.etTrainNo.requestFocus(); return false
        }
        if (from.length < 2) {
            binding.etFromStation.error = "Station code daalo (e.g. BBS)"
            binding.etFromStation.requestFocus(); return false
        }
        if (to.length < 2) {
            binding.etToStation.error = "Station code daalo (e.g. BAM)"
            binding.etToStation.requestFocus(); return false
        }
        if (selectedDate.isEmpty()) {
            Toast.makeText(this, "⚠️ Journey date select karo", Toast.LENGTH_SHORT).show()
            return false
        }

        // Departure time — auto ya manual
        val finalDepTime = getFinalDepartureTime()
        if (finalDepTime.isEmpty()) {
            Toast.makeText(this,
                "⚠️ Departure time nahi mila — manually enter karo",
                Toast.LENGTH_LONG).show()
            binding.layoutManualTime.visibility = View.VISIBLE
            return false
        }
        if (!finalDepTime.matches(Regex("\\d{1,2}:\\d{2}"))) {
            binding.etDepartureTime.error = "HH:MM format mein daalo (e.g. 05:40)"
            return false
        }
        return true
    }

    // Auto-fetched time prefer karo, fallback to manual input
    private fun getFinalDepartureTime(): String {
        return if (fetchedDepartureTime.isNotEmpty()) {
            fetchedDepartureTime
        } else {
            binding.etDepartureTime.text.toString().trim()
        }
    }

    // ─────────────────────────────────────────
    // START TRACKING
    // ─────────────────────────────────────────
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
        Toast.makeText(this, "✅ Tracking shuru! Notifications aayengi.", Toast.LENGTH_LONG).show()
    }

    // ─────────────────────────────────────────
    // UI REFRESH
    // ─────────────────────────────────────────
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

            binding.tvTrackingInfo.text =
                "Train: ${config.trainNo}  |  Class: ${config.travelClass}  |  Quota: ${config.quota}\n" +
                "${config.fromStation} → ${config.toStation}  |  ${config.dateOfJourney}\n" +
                "Departs: ${config.departureTime}"

            val emoji = when {
                lastStatus.startsWith("CURR_AVBL") -> "🟢"
                lastStatus.startsWith("AVAILABLE") -> "🔵"
                lastStatus == "REGRET"              -> "🔴"
                lastStatus.isEmpty()               -> "🟡"
                else                               -> "🟡"
            }
            binding.tvCurrentStatus.text = if (lastStatus.isEmpty()) "$emoji Checking..." else "$emoji $lastStatus"
            binding.tvLastChecked.text   = if (lastChecked.isEmpty()) "Last checked: —" else "Last checked: $lastChecked  |  Next: ~10 min"
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
