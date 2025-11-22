package com.babenko.rescueservice.core

import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.speech.tts.TextToSpeech
import com.babenko.rescueservice.R
import com.babenko.rescueservice.data.SettingsManager
import com.babenko.rescueservice.voice.TtsManager
import java.util.Locale

object NetworkMonitor {
    @Volatile private var started = false
    private var lastAlertTimestamp: Long = 0
    private const val ALERT_COOLDOWN_MS = 10000

    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Logger.d("Network available")
            }

            override fun onLost(network: Network) {
                val now = System.currentTimeMillis()
                if (now - lastAlertTimestamp > ALERT_COOLDOWN_MS) {
                    lastAlertTimestamp = now

                    Logger.d("Network lost. Firing alert.")

                    val langCode = SettingsManager.getInstance(context).getLanguage()

                    // --- START OF NEW SOLUTION ---
                    // 1. Create a locale based on the saved language
                    val locale = Locale(langCode.split("-")[0]) // Get "ru" from "ru-RU"

                    // 2. Create a special context with this locale
                    val config = Configuration(context.resources.configuration)
                    config.setLocale(locale)
                    val localizedContext = context.createConfigurationContext(config)

                    // 3. Get the string SPECIFICALLY from this context
                    val alertText = localizedContext.getString(R.string.alert_no_internet)
                    // --- END OF NEW SOLUTION ---

                    // 4. Set the language in TTS and speak the guaranteed Russian string
                    TtsManager.setLanguage(context, langCode)
                    TtsManager.speak(context, alertText, TextToSpeech.QUEUE_ADD)
                } else {
                    Logger.d("Network lost, but in cooldown period. Ignoring.")
                }
            }
        })
    }
}