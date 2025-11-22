package com.babenko.rescueservice.notifications

import android.app.Notification
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import com.babenko.rescueservice.R
import com.babenko.rescueservice.core.Logger
import com.babenko.rescueservice.data.SettingsManager
import com.babenko.rescueservice.voice.TtsManager

class RedNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        // --- NEW BLOCK TO BREAK THE CYCLE ---
        // If the notification came from our own application, ignore it.
        if (sbn.packageName == packageName) {
            Logger.d("Ignoring notification from own package: ${sbn.packageName}")
            return
        }

        val category = sbn.notification.category
        val isCall = category == Notification.CATEGORY_CALL || category?.equals("call", true) == true
        if (!isCall) return

        Logger.d("Incoming call notification detected: ${sbn.packageName}")
        checkAndNotifyIfMuted()
    }

    private fun checkAndNotifyIfMuted() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val ringMode = audioManager.ringerMode
        val isMuted = ringMode == AudioManager.RINGER_MODE_SILENT || ringMode == AudioManager.RINGER_MODE_VIBRATE
        if (!isMuted) return

// Insert in place of the removed code
        val langCode = SettingsManager.getInstance(applicationContext).getLanguage()

// 1. Create a locale based on the saved language ("ru" from "ru-RU")
        val locale = java.util.Locale(langCode.split("-")[0])

// 2. Create a special context with this locale
        val config = android.content.res.Configuration(resources.configuration)
        config.setLocale(locale)
        val localizedContext = createConfigurationContext(config)

// 3. Get the string specifically from this localized context
        val alertText = localizedContext.getString(R.string.alert_incoming_call_muted)

// 4. Set the language in TTS and speak the guaranteed correct string
        TtsManager.setLanguage(applicationContext, langCode)
        TtsManager.speak(
            applicationContext,
            alertText,
            TextToSpeech.QUEUE_FLUSH // critical - interrupt the background
        )
    }
}