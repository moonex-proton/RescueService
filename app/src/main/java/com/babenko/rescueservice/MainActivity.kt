package com.babenko.rescueservice

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.babenko.rescueservice.core.Logger
import com.babenko.rescueservice.core.NetworkMonitor
import com.babenko.rescueservice.core.PermissionsHelper
import com.babenko.rescueservice.data.SettingsManager
import com.babenko.rescueservice.databinding.ActivityMainBinding
import com.babenko.rescueservice.voice.ConversationManager
import com.babenko.rescueservice.voice.TtsManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

    private var areCoreServicesInitialized = false
    private var isSpecialAccessibilityDialogShown = false
    // NEW: to avoid spamming with the overlay dialog
    private var isOverlayDialogShown = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach {
            Logger.d("Permission ${it.key} granted: ${it.value}")
        }
        updateUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        NetworkMonitor.start(this)
        setupListeners()
        requestBasePermissions()
    }

    override fun onResume() {
        super.onResume()
        // Reset the flag every time we return to the screen (leave as is)
        isSpecialAccessibilityDialogShown = false
        isOverlayDialogShown = false
        updateUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun initializeCoreServices() {
        if (areCoreServicesInitialized) return
        Logger.d("MainActivity: All permissions granted. Initializing core audio services now.")
        TtsManager.initialize(this)
        ConversationManager.init(this)
        areCoreServicesInitialized = true
    }

    private fun requestBasePermissions() {
        val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissionsToRequest.isNotEmpty()) {
            Toast.makeText(this, R.string.requesting_base_permissions, Toast.LENGTH_SHORT).show()
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun setupListeners() {
        binding.buttonAccessibility.setOnClickListener {
            PermissionsHelper.requestAccessibilityPermission(this)
        }
        binding.buttonNotification.setOnClickListener {
            PermissionsHelper.requestNotificationListenerPermission(this)
        }
        binding.buttonBattery.setOnClickListener {
            PermissionsHelper.requestIgnoreBatteryOptimizations(this)
        }
        // Overlay button handler
        binding.buttonOverlay.setOnClickListener {
            PermissionsHelper.requestOverlayPermission(this)
        }
    }

    private fun areAllPermissionsGranted(): Boolean {
        return PermissionsHelper.isAccessibilityServiceEnabled(this) &&
                PermissionsHelper.isNotificationListenerServiceEnabled(this) &&
                PermissionsHelper.isIgnoringBatteryOptimizations(this) &&
                PermissionsHelper.isOverlayPermissionEnabled(this)
    }

    private fun updateUi() {
        // Get the current status of all permissions
        val isAccessibilityEnabled = PermissionsHelper.isAccessibilityServiceEnabled(this)
        val isNotificationListenerEnabled = PermissionsHelper.isNotificationListenerServiceEnabled(this)
        val isBatteryOptimizationIgnored = PermissionsHelper.isIgnoringBatteryOptimizations(this)
        val isOverlayEnabled = PermissionsHelper.isOverlayPermissionEnabled(this)
        Logger.d("MainActivity.updateUi: acc=$isAccessibilityEnabled, notif=$isNotificationListenerEnabled, battery=$isBatteryOptimizationIgnored, overlay=$isOverlayEnabled")

        // Update text statuses on the screen (as before)
        binding.statusAccessibility.text = if (isAccessibilityEnabled)
            getString(R.string.main_status_accessibility_on)
        else
            getString(R.string.main_status_accessibility_off)
        binding.statusNotification.text = if (isNotificationListenerEnabled)
            getString(R.string.main_status_notification_on)
        else
            getString(R.string.main_status_notification_off)
        binding.statusBattery.text = if (isBatteryOptimizationIgnored)
            getString(R.string.main_status_battery_on)
        else
            getString(R.string.main_status_battery_off)

        binding.statusOverlay.text = if (isOverlayEnabled)
            getString(R.string.main_status_overlay_on)
        else
            getString(R.string.main_status_overlay_off)

        // Proactive overlay request along with the rest of the wizard steps
        if (isAccessibilityEnabled && isNotificationListenerEnabled && isBatteryOptimizationIgnored &&
            !isOverlayEnabled && !isOverlayDialogShown) {
            isOverlayDialogShown = true
            AlertDialog.Builder(this)
                .setTitle(R.string.show_above_other_apps_title)
                .setMessage(R.string.show_above_other_apps_message)
                .setPositiveButton(R.string.dialog_common_positive) { _, _ ->
                    PermissionsHelper.requestOverlayPermission(this)
                }
                .setNegativeButton(R.string.dialog_common_negative, null)
                .show()
        }

        // Key startup logic (triggers only if ALL permissions are granted)
        if (areAllPermissionsGranted()) {
            initializeCoreServices()
            val settings = SettingsManager.getInstance(this)
            if (!settings.isUserNameSet()) {
                if (!isFinishing) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.setup_complete_title)
                        .setMessage(R.string.permissions_all_granted_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .setCancelable(false)
                        .show()
                }
                Logger.d("MainActivity: First run detected. Triggering voice setup.")
                handler.postDelayed({
                    ConversationManager.startFirstRunSetup()
                }, 1000)
            }
        }
    }
}
