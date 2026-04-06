package com.traintracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.traintracker.databinding.ActivityMainBinding
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedDate: String = ""

    companion object {
        private const val PREF_BATTERY_ASKED = "battery_opt_asked"
        private const val PREF_NAME = "AppPrefs"
    }

    // Service se broadcast aata hai → UI refresh
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TrackingService.ACTION_STATUS_UPDATED) {
                refreshUI()
            }
        }
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
        // Broadcast receiver register karo
        val filter = IntentFilter(TrackingService.ACTION_STATUS_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
        refreshUI()
    }

    override fun onPause() {
        super.onPause()
        // Broadcast receiver unregister karo
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) { }
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
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
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
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).apply {
                datePicker.minDate = System.currentTimeMillis() - 1000
            }.show()
        }
    }

    private fun setupButtons() {
        binding.btnTrack.setOnClickListener { if (validateForm()) startTracking() }
        binding.btnStop.setOnClickListener {
            TrackingService.stopManually(this)
            TrackingPrefs.setTracking(this, false)
            refreshUI()
        }
    }

    private fun validateForm(): Boolean {
        val trainNo = binding.etTrainNo.text.toString().trim()
        val from    = binding.etFromStation.text.toString().trim()
        val to      = binding.etToStation.text.toString().trim()
        val depTime = binding.etDepartureTime.text.toString().trim()

        if (trainNo.length < 4) {
            binding.etTrainNo.error = "Valid train number daalo"
            binding.etTrainNo.requestFocus(); return false
        }
        if (from.length < 2) {
            binding.etFromStation.error = "Station code daalo"
            binding.etFromStation.requestFocus(); return false
        }
        if (to.length < 2) {
            binding.etToStation.error = "Station code daalo"
            binding.etToStation.requestFocus(); return false
        }
        if (selectedDate.isEmpty()) {
            Toast.makeText(this, "Journey date select karo", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!depTime.matches(Regex("\\d{1,2}:\\d{2}"))) {
            binding.etDepartureTime.error = "HH:MM format daalo (e.g. 05:40)"
            binding.etDepartureTime.requestFocus(); return false
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
            departureTime = binding.etDepartureTime.text.toString().trim()
        )
        TrackingService.startTracking(this, config)
        refreshUI()
        Toast.makeText(this, "Tracking shuru! Har 10 min check hoga.", Toast.LENGTH_LONG).show()
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
            binding.tvCurrentStatus.text =
                if (lastStatus.isEmpty()) "$emoji Pehla check ho raha hai..."
                else "$emoji $lastStatus"
            binding.tvLastChecked.text =
                if (lastChecked.isEmpty()) "Abhi check ho raha hai..."
                else "Last: $lastChecked | Next: ~10 min"
        } else {
            binding.btnTrack.visibility     = View.VISIBLE
            binding.btnStop.visibility      = View.GONE
            binding.formCard.visibility     = View.VISIBLE
            binding.trackingCard.visibility = View.GONE
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }
}
