package com.babenko.rescueservice.core

import android.util.Log

/**
 * A simple wrapper for Logcat. Allows centralizing logging
 * and easily replacing it with a library like Timber in the future.
 */
object Logger {
    private const val TAG = "RescueService"
    private var isInitialized = false

    fun init() {
        isInitialized = true
    }

    fun d(message: String) {
        if (isInitialized) Log.d(TAG, message)
    }

    fun e(error: Throwable, message: String? = null) {
        if (isInitialized) {
            val fullMessage = message ?: "An error occurred"
            Log.e(TAG, fullMessage, error)
            // Integration with the error tracker
            ErrorTracker.track(error, fullMessage)
        }
    }
}