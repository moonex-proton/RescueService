package com.babenko.rescueservice.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.babenko.rescueservice.core.Logger

/**
 * Receives the speech recognition result from VoiceSessionService (:voice → main process)
 * and guarantees to pass the text (including empty) to ConversationManager.
 * We are NOT changing the architecture.
 */
class CommandReceiver : BroadcastReceiver() {
    companion object {
        // Must match what VoiceSessionService.processCommand(...) sends
        const val ACTION_PROCESS_COMMAND = "com.babenko.rescueservice.ACTION_PROCESS_COMMAND"
        const val EXTRA_RECOGNIZED_TEXT = "EXTRA_RECOGNIZED_TEXT"
        const val EXTRA_SCREEN_CONTEXT = "EXTRA_SCREEN_CONTEXT"
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROCESS_COMMAND) {
            Logger.d("CommandReceiver: ignored action=${intent.action}")
            return
        }
        // Take the string (even if empty) - this is critical for starting the "by button" scenario
        val text = intent.getStringExtra(EXTRA_RECOGNIZED_TEXT) ?: ""
        val screenContext = intent.getStringExtra(EXTRA_SCREEN_CONTEXT)
        Logger.d("CommandReceiver: received text='$text' and screenContext is ${if (screenContext != null) "present" else "absent"}")

        // Don't do work in onReceive: do a handoff via goAsync() to the main thread
        val pending = goAsync()
        mainHandler.post {
            try {
                // ConversationManager is an object; there is no getInstance(...) here
                ConversationManager.onUserInput(text, screenContext)
                Logger.d("CommandReceiver: forwarded text and context to ConversationManager")
            } catch (e: Exception) {
                Logger.e(e, "CommandReceiver: failed to forward text to ConversationManager")
            } finally {
                try { pending.finish() } catch (_: Exception) {}
            }
        }
    }
}
