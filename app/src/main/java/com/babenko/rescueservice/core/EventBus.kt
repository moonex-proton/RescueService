package com.babenko.rescueservice.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// Define a base class for all events in the application
sealed class AppEvent

// An event that will be sent when the TTS finishes speaking.
// The utteranceId will help to understand which phrase was spoken.
data class TtsPlaybackFinished(val utteranceId: String) : AppEvent()

// New event to request highlighting an element on the screen
data class HighlightElementEvent(val selector: Map<String, String>) : AppEvent()

data class ClickElementEvent(val selector: Map<String, String>) : AppEvent()

data class GlobalActionEvent(val actionId: Int) : AppEvent()

data class ScrollEvent(val direction: String) : AppEvent()

// Event to indicate that a long-running operation is in progress
data class ProcessingStateChanged(val isProcessing: Boolean) : AppEvent()


/**
 * A singleton EventBus for exchanging messages between application components.
 * Uses Kotlin SharedFlow for safe and efficient event delivery.
 */
object EventBus {

    // A private flow to which we will send events
    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0, // We don't need new subscribers to receive old events
        extraBufferCapacity = 1, // But we need a buffer for at least 1 event
        onBufferOverflow = BufferOverflow.DROP_OLDEST // If the buffer is full, the oldest events are discarded
    )

    // A public, read-only flow that listeners will subscribe to
    val events = _events.asSharedFlow()

    /**
     * Sends a new event to the bus.
     * @param event The event to send.
     */
    suspend fun post(event: AppEvent) {
        _events.emit(event)
    }
}