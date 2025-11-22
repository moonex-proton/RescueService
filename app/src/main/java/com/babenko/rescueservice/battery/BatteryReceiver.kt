package com.babenko.rescueservice.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import com.babenko.rescueservice.R
import com.babenko.rescueservice.core.Logger
import com.babenko.rescueservice.voice.TtsManager

/**
 * Reacts to the system's ACTION_BATTERY_LOW and speaks a warning.
 * Important: the manifest must have a receiver with an intent-filter android.intent.action.BATTERY_LOW.
 */
class BatteryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BATTERY_LOW != intent.action) return

        Logger.d("BatteryReceiver: ACTION_BATTERY_LOW captured")

        // Text from resources (you have both a short one and one for the worker; we use the main one)
        val alertText = context.getString(R.string.alert_low_battery)

        // New API: object TtsManager — call speak(context, ...)
        // ADD — so as not to interrupt critical announcements (incoming when mute, etc.)
        TtsManager.speak(
            context = context,
            text = alertText,
            queueMode = TextToSpeech.QUEUE_ADD
        )
    }
}
