package com.babenko.rescueservice.guided

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import com.babenko.rescueservice.voice.ConversationManager
import com.babenko.rescueservice.accessibility.OverlayHighlighter
import com.babenko.rescueservice.core.EventBus
import com.babenko.rescueservice.voice.TtsManager

/**
 * GuidedTaskController — an orchestrator over GuidedTaskSM.
 *
 * Responsibilities:
 * 1) Start/cancel/forced transition through the scenario steps.
 * 2) Speaking phrases through TtsManager (requires Context), reacting to TTS completion (by an external call to onTtsFinished()).
 * 3) Receiving context from the Accessibility layer (tags/extras), checking the condition of the current step
 *    and notifying the state-machine of the result.
 * 4) Managing step waiting timeouts.
 *
 * The controller does NOT contain the business logic of the steps - only coordination.
 */
class GuidedTaskController(
    private val context: Context,
    private val conversationManager: ConversationManager,
    private val overlayHighlighter: OverlayHighlighter,
    private val eventBus: EventBus
) {

    private val logTag = "GuidedTask"

    private var sm: GuidedTaskSM? = null
    private val handler = Handler(Looper.getMainLooper())
    private var activeTimer: Runnable? = null

    /** Starts a new scenario. The previous one is cancelled. */
    fun startFlow(flow: GuidedFlow) {
        cancel()
        sm = GuidedTaskSM(flow)
        process(sm!!.start())
    }

    /** Manual forced transition to the next step. */
    fun forceNext() {
        sm?.let { process(it.forceNext()) }
    }

    /** Signal about the completion of the current phrase. Called by external code upon TTS-done. */
    fun onTtsFinished() {
        sm?.let { process(it.onTtsDone()) }
    }

    /**
     * Event from the Accessibility layer.
     * @param tags   — markers of the current screen (identifiers, texts, etc.)
     * @param extras — additional data for matching
     */
    fun onAccessibilityContext(tags: Set<String>, extras: Map<String, Any?> = emptyMap()) {
        val ctx = MatchContext(tags = tags, extras = extras)
        val matched = sm?.currentStep?.expect?.matches(ctx) == true
        sm?.let { process(it.onScreenEvaluated(matched = matched, context = ctx)) }
    }

    /** Cancels the current scenario. */
    fun cancel() {
        sm?.let { process(it.cancel()) }
        sm = null
        clearTimer()
    }

    // -------------------- internal --------------------

    private fun process(actions: List<Action>) {
        for (a in actions) {
            when (a) {
                is Action.Say -> {
                    // Озвучка шага через общий TTS.
                    TtsManager.speak(
                        context = context,
                        text = context.getString(a.text.toInt()),
                        queueMode = TextToSpeech.QUEUE_ADD
                    )
                }
                is Action.SetTimer -> {
                    setTimer(a.millis)
                }
                Action.ClearTimer -> {
                    clearTimer()
                }
                Action.WaitForMatch -> {
                    // Ждём onAccessibilityContext(...) с результатом матчинга
                }
                is Action.StepChanged -> {
                    Log.d(logTag, "Step ${a.index + 1}/${a.total}: ${a.stepId}")
                    // Здесь можно опционально вызвать OverlayHighlighter,
                    // если известна цель подсветки в рамках вашего матчера.
                    // overlayHighlighter.highlight(...)
                }
                Action.Complete -> {
                    Log.d(logTag, "Flow completed")
                    cancel()
                }
                Action.Cancelled -> {
                    Log.d(logTag, "Flow cancelled")
                    cancel()
                }
            }
        }
    }

    private fun setTimer(millis: Long) {
        clearTimer()
        val r = Runnable {
            sm?.let { process(it.handle(Event.Timeout)) }
        }
        activeTimer = r
        handler.postDelayed(r, millis)
    }

    private fun clearTimer() {
        activeTimer?.let { handler.removeCallbacks(it) }
        activeTimer = null
    }
}
