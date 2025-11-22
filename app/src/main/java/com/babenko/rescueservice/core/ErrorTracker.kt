package com.babenko.rescueservice.core

import android.content.Context

/**
 * A stub for an error tracking service (Firebase Crashlytics, Sentry).
 * Sets a global handler to catch all crashes.
 */
object ErrorTracker {
    fun init(context: Context) {
        // In a real application, this is where Crashlytics/Sentry would be initialized.
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Logger.e(throwable, "UNCAUGHT EXCEPTION in thread ${thread.name}")
            // TODO (Error Tracking): Send an error report to a remote service.
        }
    }

    fun track(error: Throwable, message: String) {
        // TODO (Error Tracking): Replace with a call to your service.
        // For example: FirebaseCrashlytics.getInstance().recordException(error)
    }
}
