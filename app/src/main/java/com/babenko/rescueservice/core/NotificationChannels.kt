package com.babenko.rescueservice.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val VOICE_SESSION_CHANNEL_ID = "voice_session_channel"
    const val ALERTS_CHANNEL_ID = "alerts_channel"

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Channel for the FGS voice session (low priority, no sound)
            val voiceChannel = NotificationChannel(
                VOICE_SESSION_CHANNEL_ID,
                "Voice Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification shown when a voice session is active"
            }

            // Channel for important alerts (high priority)
            val alertsChannel = NotificationChannel(
                ALERTS_CHANNEL_ID,
                "Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for critical alerts"
            }

            notificationManager.createNotificationChannels(listOf(voiceChannel, alertsChannel))
        }
    }
}