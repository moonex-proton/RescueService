package com.babenko.rescueservice.airplane

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import com.babenko.rescueservice.R
import com.babenko.rescueservice.core.Logger
import com.babenko.rescueservice.voice.TtsManager

class AirplaneModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_AIRPLANE_MODE_CHANGED) return

        val isOn = Settings.Global.getInt(
            context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0
        ) != 0

        if (isOn) {
            Logger.d("Airplane mode ON")
            // Explicitly set the language before speaking.
            // This solves the problem of rolling back to English in a "cold" process.
            val lang = com.babenko.rescueservice.data.SettingsManager.getInstance(context).getLanguage()
            TtsManager.setLanguage(context, lang)
            TtsManager.speak(
                context,
                context.getString(R.string.alert_airplane_mode_on),
                TextToSpeech.QUEUE_ADD
            )
        } else {
            Logger.d("Airplane mode OFF")
            // We don't debounce anything here on purpose - the behavior is as requested ("as before")
        }
    }
}
