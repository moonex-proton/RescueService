package com.babenko.rescueservice.core

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AlertDialog
import com.babenko.rescueservice.R
import com.babenko.rescueservice.accessibility.RedHelperAccessibilityService

object PermissionsHelper {

    // --- Checks ---

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled: List<AccessibilityServiceInfo> =
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val target = ComponentName(context, RedHelperAccessibilityService::class.java)
        for (service in enabled) {
            val id = ComponentName.unflattenFromString(service.id)
            if (id == target) return true
        }
        return false
    }

    fun isNotificationListenerServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(":").any { it.contains(context.packageName) }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // NEW: overlay permission check
    fun isOverlayPermissionEnabled(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    // --- Requests ---

    fun requestAccessibilityPermission(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_accessibility_title)
            .setMessage(R.string.dialog_accessibility_message)
            .setPositiveButton(R.string.dialog_common_positive) { _, _ ->
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                activity.startActivity(intent)
            }
            .setNegativeButton(R.string.dialog_common_negative, null)
            .show()
    }

    fun requestNotificationListenerPermission(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_notification_title)
            .setMessage(R.string.dialog_notification_message)
            .setPositiveButton(R.string.dialog_common_positive) { _, _ ->
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                activity.startActivity(intent)
            }
            .setNegativeButton(R.string.dialog_common_negative, null)
            .show()
    }

    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_battery_title)
            .setMessage(R.string.dialog_battery_message)
            .setPositiveButton(R.string.dialog_common_positive) { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            }
            .setNegativeButton(R.string.dialog_common_negative, null)
            .show()
    }

    // NEW: overlay permission request via system settings
    fun requestOverlayPermission(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.show_above_other_apps_title)
            .setMessage(R.string.show_above_other_apps_message)
            .setPositiveButton(R.string.dialog_common_positive) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(intent)
            }
            .setNegativeButton(R.string.dialog_common_negative, null)
            .show()
    }
}
