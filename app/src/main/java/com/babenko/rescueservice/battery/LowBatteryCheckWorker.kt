package com.babenko.rescueservice.battery

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.babenko.rescueservice.R
import com.babenko.rescueservice.core.Logger
import com.babenko.rescueservice.voice.TtsManager

/**
 * Periodically checks the battery level (backup to the system's ACTION_BATTERY_LOW)
 * and speaks a warning when the level is low.
 */
class LowBatteryCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val batteryPct = BatteryUtils.getBatteryPercentage(applicationContext)
        Logger.d("Backup worker battery level: $batteryPct%")

        if (batteryPct <= 15) {
            Logger.d("Backup worker found low battery ($batteryPct%). Triggering TTS.")
            val alertText = applicationContext.getString(R.string.alert_low_battery_worker)

            // New API: object TtsManager
            TtsManager.speak(
                context = applicationContext,
                text = alertText,
                queueMode = TextToSpeech.QUEUE_ADD
            )
        } else {
            Logger.d("Backup worker found sufficient battery ($batteryPct%).")
        }

        return Result.success()
    }
}
