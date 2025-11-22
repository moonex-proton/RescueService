package com.babenko.rescueservice.data

import android.content.Context
import android.content.SharedPreferences
import com.babenko.rescueservice.R

/**
 * A unified manager for application settings.
 * Stores the TTS/ASR language and speech rate.
 */
class SettingsManager private constructor(private var context: Context) {

    private lateinit var prefs: SharedPreferences

    init {
        loadSettings()
    }

    /**
     * Forces a reload of the SharedPreferences from disk.
     * This is crucial for inter-process communication on newer Android versions
     * where MODE_MULTI_PROCESS is ignored.
     */
    fun loadSettings() {
        val prefName = "${context.packageName}_preferences"
        prefs = context.getSharedPreferences(prefName, Context.MODE_MULTI_PROCESS)
    }

    // --- Language ---
    fun saveLanguage(language: String) {
        prefs.edit().putString(KEY_LANGUAGE, language).apply()
    }

    fun getLanguage(): String {
        // Reload before reading to get the latest value from other processes.
        loadSettings()
        return prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    // --- Speech rate ---
    fun saveSpeechRate(rate: Float) {
        prefs.edit().putFloat(KEY_SPEECH_RATE, rate).apply()
    }

    fun getSpeechRate(): Float {
        return prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
    }

    // --- User name ---
    fun saveUserName(name: String) {
        prefs.edit().putString(KEY_USER_NAME, name).apply()
    }

    fun getUserName(): String {
        loadSettings()
        val storedName = prefs.getString(KEY_USER_NAME, null)
        if (storedName != null) {
            return storedName
        }

        return context.getString(R.string.default_user_name)
    }

    /**
     * Returns true if the user name has been set before.
     * Used to determine the first run.
     */
    fun isUserNameSet(): Boolean {
        return prefs.contains(KEY_USER_NAME)
    }

    companion object {
        private const val KEY_LANGUAGE = "language"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_USER_NAME = "user_name"

        // IMPORTANT: English is the default out of the box
        const val DEFAULT_LANGUAGE: String = "en-US"
        const val DEFAULT_SPEECH_RATE: Float = 1.0f

        @Volatile private var INSTANCE: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Updates the context in the existing singleton instance.
         * This is necessary to correctly reload string resources after a locale change.
         */
        fun updateContext(newContext: Context) {
            INSTANCE?.let {
                it.context = newContext
            }
        }
    }
}