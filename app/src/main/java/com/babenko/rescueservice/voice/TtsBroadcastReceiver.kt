package com.babenko.rescueservice.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import com.babenko.rescueservice.core.Logger

/**
 * This BroadcastReceiver listens for text-to-speech requests from other processes (e.g., VoiceSessionService).
 * It receives an Intent with the ACTION_SPEAK action, extracts the text from EXTRA_TEXT_TO_SPEAK,
 * and passes it to the TtsManager, which runs in the main process.
 */
class TtsBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SPEAK) {
            val textToSpeak = intent.getStringExtra(EXTRA_TEXT_TO_SPEAK)
            if (!textToSpeak.isNullOrBlank()) {
                Logger.d("TtsBroadcastReceiver: Received speak request with text: '$textToSpeak'")
                // IMPORTANT: use QUEUE_ADD so as not to interrupt the current TTS replica.
                // Audio focus management is now centralized in TtsManager.
                TtsManager.speak(context, textToSpeak, TextToSpeech.QUEUE_ADD)
            }
        }
    }

    companion object {
        // A unique address for our messages
        const val ACTION_SPEAK = "com.babenko.rescueservice.ACTION_SPEAK"
        // The key under which the text to be spoken will be in the message
        const val EXTRA_TEXT_TO_SPEAK = "EXTRA_TEXT_TO_SPEAK"
    }
}
