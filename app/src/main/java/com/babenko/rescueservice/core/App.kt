package com.babenko.rescueservice.core

import android.app.Application
import androidx.preference.PreferenceManager
import com.babenko.rescueservice.data.SettingsManager
import com.babenko.rescueservice.llm.LlmClient
import com.babenko.rescueservice.voice.ConversationManager
import com.babenko.rescueservice.voice.TtsManager
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize safe components (without audio resources/permissions)
        Logger.init()
        ErrorTracker.init(this)
        NotificationChannels.createAll(this)
        LlmClient.init(this)

        // Reset SharedPreferences on a fresh install (or restore on a new device)
        resetPrefsIfFreshInstall()

        // Then, the usual initialization
        SettingsManager.getInstance(this)
        AssistantLifecycleManager.init(this)

        // Safe initialization of ConversationManager (don't touch the audio)
        ConversationManager.init(this)

        applyDefaultsIfNeeded()
        Logger.d("RescueService application started")
    }

    /**
     * Clears SharedPreferences once on a "clean" install:
     * - after uninstall→install, or
     * - when installing on a new device.
     *
     * We use a marker in noBackupFilesDir—it doesn't get backed up to the cloud,
     * so its absence on the device means it's the first install on this device.
     */
    private fun resetPrefsIfFreshInstall() {
        val marker = File(noBackupFilesDir, "install_marker")
        if (!marker.exists()) {
            Logger.d("Fresh install detected. Clearing SharedPreferences.")
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .edit()
                .clear()
                .apply()
            runCatching { marker.createNewFile() }
                .onFailure { e -> Logger.e(e, "Failed to create install marker") }
        }
    }

    private fun applyDefaultsIfNeeded() {
        val settings = SettingsManager.getInstance(this)
        val isFirstRun = !settings.isUserNameSet()
        if (isFirstRun) {
            settings.saveLanguage(SettingsManager.DEFAULT_LANGUAGE)
            settings.saveSpeechRate(SettingsManager.DEFAULT_SPEECH_RATE)
            Logger.d("First run detected. Applying default language and speech rate.")
        }
    }
}
