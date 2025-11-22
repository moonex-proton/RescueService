package com.babenko.rescueservice.guided

import androidx.annotation.StringRes

/**
 * The CORE state-machine for "dialog-action".
 * Pure Kotlin (no Android dependencies) - to make it easy to test.
 */

// ... (data classes and interfaces remain the same)

data class MatchContext(
    val tags: Set<String> = emptySet(),
    val extras: Map<String, Any?> = emptyMap()
)

fun interface Condition {
    fun matches(ctx: MatchContext): Boolean
}

data class GuidedStep(
    val id: String,
    @StringRes val say: Int,
    val expect: Condition? = null,
    val timeoutMs: Long = 0L,
    @StringRes val onTimeoutRepeatSay: Int? = null
)

data class GuidedFlow(
    val id: String,
    val steps: List<GuidedStep>
) {
    init {
        require(steps.isNotEmpty()) { "GuidedFlow must contain at least one step" }
        require(steps.map { it.id }.toSet().size == steps.size) { "Step ids must be unique" }
    }
}

sealed interface Event {
    data object Start : Event
    data object TtsDone : Event
    data class ScreenEvaluated(val matched: Boolean, val context: MatchContext? = null) : Event
    data object Timeout : Event
    data object ForceNext : Event
    data object Cancel : Event
}

sealed interface Action {
    data class Say(val text: String) : Action
    data class SetTimer(val millis: Long) : Action
    data object ClearTimer : Action
    data object WaitForMatch : Action
    data class StepChanged(val stepId: String, val index: Int, val total: Int) : Action
    data object Complete : Action
    data object Cancelled : Action
}

private enum class InternalState { IDLE, SPEAKING, WAITING_MATCH, COMPLETED, CANCELLED }

class GuidedTaskSM(
    private val flow: GuidedFlow
) {
    var sessionId: String? = null

    private var state: InternalState = InternalState.IDLE
    private var index: Int = 0

    val currentStep: GuidedStep?
        get() = flow.steps.getOrNull(index)

    val totalSteps: Int get() = flow.steps.size

    fun handle(event: Event): List<Action> {
        return when (state) {
            InternalState.IDLE -> onIdle(event)
            InternalState.SPEAKING -> onSpeaking(event)
            InternalState.WAITING_MATCH -> onWaiting(event)
            InternalState.COMPLETED, InternalState.CANCELLED -> emptyList()
        }
    }

    private fun onIdle(event: Event): List<Action> = when (event) {
        Event.Start -> {
            index = 0
            val step = requireNotNull(currentStep)
            state = InternalState.SPEAKING
            buildList {
                add(Action.StepChanged(step.id, index, totalSteps))
                add(Action.Say(step.say.toString()))
            }
        }
        is Event.Cancel -> {
            state = InternalState.CANCELLED
            sessionId = null
            listOf(Action.Cancelled)
        }
        else -> emptyList()
    }

    private fun onSpeaking(event: Event): List<Action> = when (event) {
        Event.TtsDone -> {
            val step = requireNotNull(currentStep)
            if (step.expect == null) advance() else {
                state = InternalState.WAITING_MATCH
                buildList {
                    add(Action.WaitForMatch)
                    if (step.timeoutMs > 0) add(Action.SetTimer(step.timeoutMs))
                }
            }
        }
        is Event.ForceNext -> advance()
        is Event.Cancel -> {
            state = InternalState.CANCELLED
            sessionId = null
            listOf(Action.Cancelled)
        }
        else -> emptyList()
    }

    private fun onWaiting(event: Event): List<Action> = when (event) {
        is Event.ScreenEvaluated -> {
            if (!event.matched) emptyList() else {
                buildList {
                    add(Action.ClearTimer)
                    addAll(advance())
                }
            }
        }
        Event.Timeout -> {
            val step = requireNotNull(currentStep)
            buildList {
                add(Action.Say((step.onTimeoutRepeatSay ?: step.say).toString()))
                if (step.timeoutMs > 0) add(Action.SetTimer(step.timeoutMs))
            }
        }
        Event.ForceNext -> {
            buildList {
                add(Action.ClearTimer)
                addAll(advance())
            }
        }
        is Event.Cancel -> {
            state = InternalState.CANCELLED
            sessionId = null
            listOf(Action.ClearTimer, Action.Cancelled)
        }
        else -> emptyList()
    }

    private fun advance(): List<Action> {
        val nextIndex = index + 1
        if (nextIndex >= totalSteps) {
            state = InternalState.COMPLETED
            sessionId = null
            return listOf(Action.Complete)
        }
        index = nextIndex
        val step = requireNotNull(currentStep)
        state = InternalState.SPEAKING
        return buildList {
            add(Action.StepChanged(step.id, index, totalSteps))
            add(Action.Say(step.say.toString()))
        }
    }

    fun onTtsDone(): List<Action> = handle(Event.TtsDone)
    fun onScreenEvaluated(matched: Boolean, context: MatchContext? = null): List<Action> =
        handle(Event.ScreenEvaluated(matched, context))
    fun forceNext(): List<Action> = handle(Event.ForceNext)
    fun cancel(): List<Action> = handle(Event.Cancel)
    fun start(): List<Action> = handle(Event.Start)
}

class AnyTag(private val anyOf: Set<String>) : Condition {
    override fun matches(ctx: MatchContext): Boolean = ctx.tags.any { it in anyOf }
}

class AllTags(private val all: Set<String>) : Condition {
    override fun matches(ctx: MatchContext): Boolean = all.all { it in ctx.tags }
}

class ExtraEquals(private val key: String, private val expected: Any?) : Condition {
    override fun matches(ctx: MatchContext): Boolean = ctx.extras[key] == expected
}

infix fun Condition.and(other: Condition): Condition = Condition { ctx -> this.matches(ctx) && other.matches(ctx) }

infix fun Condition.or(other: Condition): Condition = Condition { ctx -> this.matches(ctx) || other.matches(ctx) }

operator fun Condition.not(): Condition = Condition { ctx -> !this.matches(ctx) }
