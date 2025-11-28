package com.babenko.rescueservice.core

import android.content.Context
import com.babenko.rescueservice.voice.VoiceSessionService
import com.babenko.rescueservice.voice.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * A singleton for managing the assistant's lifecycle and reacting to system events.
 * We are not changing the architecture.
 * "Dialog mode":
 * after the TTS finishes, we open a short follow-up window,
 * during which a screen change event can start SR without a new voice replica.
 */
object AssistantLifecycleManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isInitialized = false

    // --- FOLLOW-UP WINDOW ---
    private const val DEFAULT_FOLLOW_UP_WINDOW_MS = 60_000L
    private var followUpWindowUntil: Long = 0L
    private var followUpCloser: Job? = null
    private var lastInstructionGiven: String? = null

    /**
     * Public getter for the last spoken instruction.
     * Needed by ConversationManager: the assistant includes it in the context of the request to the LLM.
     */
    @JvmStatic
    fun getLastInstructionGiven(): String? = lastInstructionGiven

    /**
     * Is the follow-up window currently active.
     * Used by the accessibility service to decide whether to send a follow-up on screen change or not.
     */
    @JvmStatic
    fun isFollowUpWindowActive(): Boolean = System.currentTimeMillis() < followUpWindowUntil

    /**
     * Explicitly close the observation window (e.g., when the user starts voice input).
     */
    @JvmStatic
    fun cancelFollowUpWindow() {
        followUpCloser?.cancel()
        followUpCloser = null
        followUpWindowUntil = 0L
        Logger.d("Follow-up window cancelled.")
    }

    private fun startFollowUpWindow(durationMs: Long = DEFAULT_FOLLOW_UP_WINDOW_MS) {
        val now = System.currentTimeMillis()
        followUpWindowUntil = now + durationMs
        // restart the window closing
        followUpCloser?.cancel()
        followUpCloser = scope.launch {
            delay(durationMs)
            if (System.currentTimeMillis() >= followUpWindowUntil) {
                followUpWindowUntil = 0L
                Logger.d("Follow-up window closed by timeout.")
            }
        }
        Logger.d("Follow-up window started for ${durationMs} ms (until=$followUpWindowUntil).")
    }

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        Logger.d("AssistantLifecycleManager initializing...")
        subscribeToEvents(context.applicationContext)
        // Global start of network monitoring (as before - without changing the contract)
        NetworkMonitor.start(context.applicationContext)
    }

    private fun subscribeToEvents(context: Context) {
        scope.launch {
            EventBus.events.collectLatest { event ->
                when (event) {
                    is TtsPlaybackFinished -> {
                        // Record the last instruction (for subsequent follow-up / LLM context)
                        lastInstructionGiven = TtsManager.lastSpokenMessage
                        // Open the screen change observation window
                        startFollowUpWindow()
                        Logger.d("Event received: TtsPlaybackFinished. Starting follow-up window and SR session (10s).")
                        // Keep the current behavior: start an SR session (backward compatibility M0)
                        VoiceSessionService.startSession(context, timeoutSeconds = 10)
                    }
                    else -> {
                        // Ignore other events, such as HighlightElementEvent,
                        // as they are not intended for this manager.
                    }
                }
            }
        }
    }

    /**
     * External point for the Accessibility service:
     * when the screen changes in the active follow-up window - start a short SR session.
     */
    @JvmStatic
    fun onScreenChangedForFollowUp(context: Context, screenContext: String?) {
        if (!isFollowUpWindowActive()) {
            return
        }
        // DO NOT start the voice session here. This created a race condition.
        // The voice session will be started by the TtsPlaybackFinished event handler,
        // which ensures the microphone only opens AFTER the LLM's response has been spoken.
        // This method now only serves to validate that the screen change occurred
        // within the valid time window. The Accessibility Service already sent the
        // broadcast to trigger the "FOLLOW_UP" to the LLM.
        Logger.d("Screen changed during follow-up window. Logic proceeds, but voice session is deferred.")
    }
}