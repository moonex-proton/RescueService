package com.babenko.rescueservice.voice

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import com.babenko.rescueservice.R
import com.babenko.rescueservice.accessibility.RedHelperAccessibilityService
import com.babenko.rescueservice.core.AssistantLifecycleManager
import com.babenko.rescueservice.core.ClickElementEvent
import com.babenko.rescueservice.core.EventBus
import com.babenko.rescueservice.core.GlobalActionEvent
import com.babenko.rescueservice.core.HighlightElementEvent
import com.babenko.rescueservice.core.Logger
import com.babenko.rescueservice.core.ProcessingStateChanged
import com.babenko.rescueservice.core.ScrollEvent
import com.babenko.rescueservice.data.SettingsManager
import com.babenko.rescueservice.llm.Command
import com.babenko.rescueservice.llm.CommandParser
import com.babenko.rescueservice.llm.DeviceStatus
import com.babenko.rescueservice.llm.LlmClient
import com.babenko.rescueservice.llm.ParsedCommand
import com.babenko.rescueservice.llm.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.UUID
import kotlin.math.round

object ConversationManager {
    const val ACTION_LOCALE_CHANGED = "com.babenko.rescueservice.ACTION_LOCALE_CHANGED"

    private lateinit var appContext: Context
    private val ready get() = ::appContext.isInitialized
    private var awaitingFirstRunName = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // --- SESSION MANAGEMENT ---
    private var currentSessionId: String? = null

    // --- TASK STATE MANAGEMENT (TOTEM) ---
    private var currentTaskState = TaskState()

    private enum class State {
        IDLE, AWAITING_SETTING_CHOICE, AWAITING_NEW_NAME, AWAITING_NEW_LANGUAGE, AWAITING_NEW_SPEED
    }

    private var currentState = State.IDLE
    private var isRetryAttempt = false
    private val settings: SettingsManager by lazy { SettingsManager.getInstance(appContext) }

    fun init(context: Context) {
        appContext = context.applicationContext
        Logger.d("ConversationManager initialized")
    }

    fun startFirstRunSetup() {
        if (!ready) return
        awaitingFirstRunName = true
        // --- CHANGE: Set red state immediately ---
        scope.launch { EventBus.post(ProcessingStateChanged(true)) }
        val welcomeMessage = appContext.getString(R.string.welcome_message)
        TtsManager.speak(context = appContext, text = welcomeMessage, queueMode = TextToSpeech.QUEUE_FLUSH, onDone = {
            VoiceSessionService.startSession(context = appContext, timeoutSeconds = 15)
        })
    }

    fun onUserInput(text: String, screenContext: String?) {
        if (!ready) return
        when (currentState) {
            State.IDLE -> handleIdleState(text, screenContext)
            State.AWAITING_SETTING_CHOICE -> handleSettingChoice(text)
            State.AWAITING_NEW_NAME -> handleNewName(text)
            State.AWAITING_NEW_LANGUAGE -> handleNewLanguage(text)
            State.AWAITING_NEW_SPEED -> handleNewSpeed(text)
        }
    }

    private fun handleIdleState(text: String, screenContext: String?) {
        if (awaitingFirstRunName) {
            handleFirstRunName(text)
            return
        }

        if (text.equals("FOLLOW_UP", ignoreCase = true)) {
            Logger.d("Handling FOLLOW_UP event.")
            queryLlm(text, screenContext)
            return
        }

        val parsedCommand = CommandParser.parse(text)
        if (parsedCommand.command == Command.UNKNOWN) {
            val primaryText = text.split("|||").first().trim()
            // Если команда не распознана, выполните запрос LLM.
            if (primaryText.isNotBlank()) {
                queryLlm(primaryText, screenContext)
            }
        } else {
            isRetryAttempt = false
            // CHANGE: Pass original text and screen context for fallback
            val originalText = text.split("|||").first().trim()
            processLocalCommand(parsedCommand, originalText, screenContext)
        }
    }

    // --- REFACTORING THE LLM CALL ---
    private fun queryLlm(userText: String, screenContext: String?) {
        scope.launch {
            try {
                EventBus.post(ProcessingStateChanged(true))
                // --- FUSE PROTECTION (Client-side) ---
                if (currentTaskState.goal != "NONE" && currentTaskState.step > 5) {
                    Logger.d("Fuse triggered: Step limit > 5. Stopping task.")
                    // Reset state
                    currentTaskState = TaskState()
                    // Inform user
                    val msg = "Я искала слишком долго, но не нашла. Давайте попробуем иначе." // Hardcoded fallback or resource
                    TtsManager.speak(appContext, msg, TextToSpeech.QUEUE_FLUSH, onDone = {
                        scope.launch { EventBus.post(ProcessingStateChanged(false)) }
                    })
                    return@launch
                }

                // Increment step if we are in a task
                if (currentTaskState.goal != "NONE") {
                    currentTaskState = currentTaskState.copy(step = currentTaskState.step + 1)
                }

                // 1. Get or create a session ID
                val sessionId = currentSessionId ?: UUID.randomUUID().toString().also {
                    currentSessionId = it
                    Logger.d("Starting new LLM session: $it")
                }

                // 2. Gather status and send the request through the new function
                val statusJson = Json.encodeToString(gatherDeviceStatus())
                val llmResponse = LlmClient.assist(
                    sessionId = sessionId,
                    userText = userText,
                    screenContext = screenContext,
                    status = statusJson,
                    taskState = currentTaskState // Pass the Totem
                )

                // 3. Speak and process the response
                val textToSpeak = llmResponse.reply_text
                // Calculate effective text (check if it is empty after sanitation)
                val effectiveText = (textToSpeak ?: "").replace(Regex("[*#`~_]+"), " ").trim()
                val hasActions = !llmResponse.actions.isNullOrEmpty()

                if (effectiveText.isBlank() && !hasActions) {
                    // NEW: Log fallback triggering
                    Logger.d("LLM returned empty result. Speaking fallback.")

                    // LLM returned nothing useful (silence) -> Speak fallback
                    val lang = settings.getLanguage()
                    val fallback = if (lang.startsWith("ru", ignoreCase = true))
                        "Говорит созвездие Орион, попробуйте еще раз"
                    else
                        "This is Orion constellation speaking, please try again"

                    TtsManager.speak(appContext, fallback, TextToSpeech.QUEUE_FLUSH, onDone = {
                        scope.launch { EventBus.post(ProcessingStateChanged(false)) }
                    })
                } else {
                    // Normal flow
                    if (!textToSpeak.isNullOrBlank()) {
                        TtsManager.speak(appContext, textToSpeak, TextToSpeech.QUEUE_FLUSH, onDone = {
                            scope.launch { EventBus.post(ProcessingStateChanged(false)) }
                        })
                    } else {
                        // If there is no text to speak, send the event that the processing is finished
                        scope.launch { EventBus.post(ProcessingStateChanged(false)) }
                    }

                    if (hasActions) {
                        processActions(llmResponse.actions!!)
                    }
                }

            } catch (e: Exception) {
                Logger.e(e, "Failed to get response from LLM.")
                val fallbackMessage = appContext.getString(R.string.llm_error_fallback)
                TtsManager.speak(appContext, fallbackMessage, TextToSpeech.QUEUE_FLUSH, onDone = {
                    scope.launch { EventBus.post(ProcessingStateChanged(false)) }
                })
            }
        }
    }

    private fun handleSettingChoice(text: String) {
        val parsed = CommandParser.parse(text)
        when (parsed.command) {
            Command.INTENT_CHANGE_NAME -> {
                isRetryAttempt = false
                val phrase = appContext.getString(R.string.choose_name_prompt)
                speakAndListen(phrase, State.AWAITING_NEW_NAME)
            }
            Command.INTENT_CHANGE_LANGUAGE -> {
                isRetryAttempt = false
                val phrase = appContext.getString(R.string.choose_language_prompt)
                speakAndListen(phrase, State.AWAITING_NEW_LANGUAGE)
            }
            Command.INTENT_CHANGE_SPEED -> {
                val phrase = appContext.getString(R.string.choose_speed_prompt)
                speakAndListen(phrase, State.AWAITING_NEW_SPEED)
            }
            else -> {
                if (!isRetryAttempt) {
                    isRetryAttempt = true
                    val phrase = appContext.getString(R.string.didnt_understand_rephrase)
                    speakAndListen(phrase, State.AWAITING_SETTING_CHOICE)
                } else {
                    Logger.d("Second unknown command in settings. Exiting settings dialog.")
                    resetToIdle()
                }
            }
        }
    }

    private fun handleNewName(text: String) {
        if (text.isNotBlank()) {
            val newName = text.split("|||").first().trim()
            settings.saveUserName(newName)
            val phrase = appContext.getString(R.string.name_confirmation, newName)
            TtsManager.speak(appContext, phrase, TextToSpeech.QUEUE_ADD, onDone = {
                // Ensure state is reset after confirmation
                scope.launch { EventBus.post(ProcessingStateChanged(false)) }
            })
        } else {
            resetToIdle()
        }
    }

    private fun handleNewLanguage(text: String) {
        val primaryText = text.split("|||").first().trim()
        val targetLanguage = detectLanguageTarget(primaryText)

        if (targetLanguage != null) {
            isRetryAttempt = false
            applyLanguage(targetLanguage)
            // Use localized context to get the string in the new language immediately
            val localizedContext = getLocalizedContext(appContext, targetLanguage)
            val phrase = localizedContext.getString(R.string.language_set_confirmation)

            TtsManager.speak(appContext, phrase, TextToSpeech.QUEUE_ADD, onDone = {
                scope.launch { EventBus.post(ProcessingStateChanged(false)) }
            })
            resetToIdle()
        } else {
            if (!isRetryAttempt) {
                isRetryAttempt = true
                val potentialLang = primaryText.split(" ").lastOrNull { it.length > 2 } ?: primaryText
                val phrase = appContext.getString(R.string.language_not_recognized_reprompt, potentialLang)
                speakAndListen(phrase, State.AWAITING_NEW_LANGUAGE)
            } else {
                val phrase = appContext.getString(R.string.language_not_recognized_exit)
                TtsManager.speak(appContext, phrase, TextToSpeech.QUEUE_ADD, onDone = {
                    scope.launch { EventBus.post(ProcessingStateChanged(false)) }
                })
                resetToIdle()
            }
        }
    }

    private fun handleNewSpeed(text: String) {
        val primaryText = text.split("|||").first().trim()

        // First, try context-specific parsing for simple words like "faster"
        var command = detectSpeedChange(primaryText)

        // If that fails, fall back to the global command parser for full phrases like "speak faster"
        if (command == Command.UNKNOWN) {
            command = CommandParser.parse(text).command
        }

        var commandProcessed = false
        when (command) {
            Command.CHANGE_SPEECH_RATE_FASTER -> {
                bumpSpeechRate(0.2f)
                val newSpeedDesc = describeSpeechRate()
                val phrase = appContext.getString(R.string.speed_confirmation, newSpeedDesc)
                TtsManager.speak(appContext, phrase, TextToSpeech.QUEUE_ADD, onDone = {
                    scope.launch { EventBus.post(ProcessingStateChanged(false)) }
                })
                commandProcessed = true
            }
            Command.CHANGE_SPEECH_RATE_SLOWER -> {
                bumpSpeechRate(-0.2f)
                val newSpeedDesc = describeSpeechRate()
                val phrase = appContext.getString(R.string.speed_confirmation, newSpeedDesc)
                TtsManager.speak(appContext, phrase, TextToSpeech.QUEUE_ADD, onDone = {
                    scope.launch { EventBus.post(ProcessingStateChanged(false)) }
                })
                commandProcessed = true
            }
            else -> {
                // Command is not for speed change, do nothing here.
            }
        }

        if (commandProcessed) {
            isRetryAttempt = false
            resetToIdle()
        } else {
            if (!isRetryAttempt) {
                isRetryAttempt = true
                val phrase = appContext.getString(R.string.didnt_understand_speed)
                speakAndListen(phrase, State.AWAITING_NEW_SPEED)
            } else {
                val phrase = appContext.getString(R.string.command_not_recognized_exit)
                TtsManager.speak(appContext, phrase, TextToSpeech.QUEUE_ADD, onDone = {
                    scope.launch { EventBus.post(ProcessingStateChanged(false)) }
                })
                resetToIdle()
            }
        }
    }

    private fun resetToIdle() {
        currentState = State.IDLE
        isRetryAttempt = false
        // --- RESETTING THE SESSION ---
        currentSessionId = null
        Logger.d("ConversationManager state and session reset to IDLE.")
        // --- CHANGE: Always ensure green state on reset ---
        scope.launch { EventBus.post(ProcessingStateChanged(false)) }
    }

    private fun speakAndListen(text: String, nextState: State, timeout: Int = 15) {
        // --- CHANGE: Set red state at start of dialog turn ---
        scope.launch { EventBus.post(ProcessingStateChanged(true)) }
        currentState = nextState
        Logger.d("Transitioning to state $nextState")
        TtsManager.speak(context = appContext, text = text, queueMode = TextToSpeech.QUEUE_FLUSH, onDone = {
            VoiceSessionService.startSession(appContext, timeoutSeconds = timeout)
        })
    }

    private fun handleFirstRunName(text: String) {
        // --- CHANGE: Set red state while processing setup ---
        scope.launch { EventBus.post(ProcessingStateChanged(true)) }
        awaitingFirstRunName = false
        val confirmationPhrase: String
        if (text.isNotBlank()) {
            val trimmedName = text.trim()
            settings.saveUserName(trimmedName)
            confirmationPhrase = appContext.getString(R.string.name_confirmation, trimmedName)
        } else {
            val defaultName = settings.getUserName()
            settings.saveUserName(defaultName)
            confirmationPhrase = appContext.getString(R.string.default_name_confirmation, defaultName)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(audioAttributes).setAcceptsDelayedFocusGain(true).setOnAudioFocusChangeListener { }.build()
            if (audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                TtsManager.shutdown()
                TtsManager.initialize(appContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        TtsManager.speak(context = appContext, text = confirmationPhrase, queueMode = TextToSpeech.QUEUE_ADD, onDone = {
                            speakFinalSettings(appContext)
                        })
                    }
                }
            }
        }, 500)
    }

    // CHANGE: Update signature to accept original text and screen context for fallback
    private fun processLocalCommand(parsed: ParsedCommand, originalText: String? = null, screenContext: String? = null) {
        // --- CHANGE: Set red state during command processing ---
        scope.launch { EventBus.post(ProcessingStateChanged(true)) }

        // Explicitly typed as () -> Unit to avoid "Unit conversion" compiler error
        val onComplete: () -> Unit = {
            scope.launch { EventBus.post(ProcessingStateChanged(false)) }
        }

        when (parsed.command) {
            Command.CHANGE_NAME -> {
                parsed.payload?.takeIf { it.isNotBlank() }?.let { newName ->
                    settings.saveUserName(newName)
                    val phrase = appContext.getString(R.string.name_confirmation, newName)
                    TtsManager.speak(appContext, phrase, TextToSpeech.QUEUE_ADD, onDone = onComplete)
                } ?: onComplete()
            }
            Command.CHANGE_SPEECH_RATE_FASTER -> {
                bumpSpeechRate(0.2f)
                val phrase = appContext.getString(R.string.speak_faster_confirmation)
                TtsManager.speak(appContext, phrase, TextToSpeech.QUEUE_ADD, onDone = onComplete)
            }
            Command.CHANGE_SPEECH_RATE_SLOWER -> {
                bumpSpeechRate(-0.2f)
                val phrase = appContext.getString(R.string.speak_slower_confirmation)
                TtsManager.speak(appContext, phrase, TextToSpeech.QUEUE_ADD, onDone = onComplete)
            }
            Command.CHANGE_LANGUAGE -> {
                val target = parsed.payload ?: autoToggleLanguage()
                applyLanguage(target)
                // Use localized context to get the string in the new language immediately
                val localizedContext = getLocalizedContext(appContext, target)
                val phrase = localizedContext.getString(R.string.language_set_confirmation)

                TtsManager.speak(appContext, phrase, TextToSpeech.QUEUE_ADD, onDone = onComplete)
            }
            // --- SCROLL COMMANDS HANDLED HERE ---
            Command.SCROLL_DOWN -> {
                Logger.d("Executing local command: SCROLL_DOWN")
                scope.launch { EventBus.post(ScrollEvent("down")) }
                scheduleForceCapture()
                onComplete()
            }
            Command.SCROLL_UP -> {
                Logger.d("Executing local command: SCROLL_UP")
                scope.launch { EventBus.post(ScrollEvent("up")) }
                scheduleForceCapture()
                onComplete()
            }
            // --- APP LAUNCH COMMANDS ---
            Command.OPEN_APP -> {
                val appNameQuery = parsed.payload
                if (!appNameQuery.isNullOrBlank()) {
                    Logger.d("Executing local command: OPEN_APP query='$appNameQuery'")

                    // 1. Speak "Starting..." BEFORE launching
                    val lang = settings.getLanguage()
                    val startPhrase = if (lang.startsWith("ru", true)) "Запускаю $appNameQuery..." else "Starting $appNameQuery..."
                    TtsManager.speak(appContext, startPhrase, TextToSpeech.QUEUE_ADD)

                    // 2. Launch
                    val (launched, remainder) = launchAppByName(appNameQuery)

                    if (launched) {
                        Logger.d("App launched locally. Remainder: '$remainder'")
                        // If there is a remainder, set it as the goal for the next step (when app opens)
                        if (remainder.isNotBlank()) {
                            currentTaskState = TaskState(goal = remainder, step = 0)
                            // FORCE capture to ensure we don't end in silence if user asked to "chat"
                            scheduleForceCapture()
                        }
                        onComplete()
                    } else {
                        // Fallback to LLM if local launch fails
                        Logger.d("App not found locally. Falling back to LLM.")
                        // Also force capture here to reset potential stale state and ensure freshness
                        scheduleForceCapture()

                        if (originalText != null) {
                            // Don't call onComplete here, LLM processing will handle the state
                            queryLlm(originalText, screenContext)
                        } else {
                            // Should not happen, but safe fallback
                            val msg = if (lang.startsWith("ru")) "Приложение не найдено" else "App not found"
                            TtsManager.speak(appContext, msg, TextToSpeech.QUEUE_ADD, onDone = onComplete)
                        }
                    }
                } else {
                    onComplete()
                }
            }
            // ------------------------------------
            // --- NEW HANDLERS FOR DIRECT INTENTS ---
            Command.INTENT_CHANGE_NAME -> {
                val phrase = appContext.getString(R.string.choose_name_prompt)
                speakAndListen(phrase, State.AWAITING_NEW_NAME)
            }
            Command.INTENT_CHANGE_LANGUAGE -> {
                val phrase = appContext.getString(R.string.choose_language_prompt)
                speakAndListen(phrase, State.AWAITING_NEW_LANGUAGE)
            }
            Command.INTENT_CHANGE_SPEED -> {
                val phrase = appContext.getString(R.string.choose_speed_prompt)
                speakAndListen(phrase, State.AWAITING_NEW_SPEED)
            }
            // ---------------------------------------
            Command.OPEN_SETTINGS -> {
                val phrase = appContext.getString(R.string.open_settings_prompt)
                speakAndListen(phrase, State.AWAITING_SETTING_CHOICE)
            }
            else -> {
                onComplete()
            }
        }
    }

    // CHANGE: Updated logic to return pair (Success, Remainder) and handle complex queries
    private fun launchAppByName(query: String): Pair<Boolean, String> {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        // Get all launchable apps
        val apps = pm.queryIntentActivities(intent, 0)
        val q = query.trim().lowercase()

        // 1. Try to find an app whose label is the PREFIX of the query.
        // Useful for: "open whatsapp chat..." -> matches "whatsapp"
        // We pick the longest matching app label to avoid false positives (e.g. "Google" vs "Google Maps")
        val bestPrefixMatch = apps.filter {
            val label = it.loadLabel(pm).toString().lowercase()
            // Check if query starts with label (e.g. "whatsapp chat" starts with "whatsapp")
            q.startsWith(label)
        }.maxByOrNull { it.loadLabel(pm).toString().length }

        if (bestPrefixMatch != null) {
            val label = bestPrefixMatch.loadLabel(pm).toString().lowercase()
            val remainder = q.removePrefix(label).trim()
            val launchIntent = pm.getLaunchIntentForPackage(bestPrefixMatch.activityInfo.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(launchIntent)
                return true to remainder
            }
        }

        // 2. Fallback: Check if app label contains the query (fuzzy match for simple/short queries)
        // Useful for: "open whats" -> matches "whatsapp"
        val partialMatch = apps.firstOrNull {
            val label = it.loadLabel(pm).toString().lowercase()
            label.contains(q)
        }

        if (partialMatch != null) {
            val launchIntent = pm.getLaunchIntentForPackage(partialMatch.activityInfo.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(launchIntent)
                return true to ""
            }
        }

        return false to ""
    }

    fun speakFinalSettings(context: Context) {
        scope.launch { EventBus.post(ProcessingStateChanged(true)) }
        val finalName = settings.getUserName()
        val languageName = appContext.getString(R.string.language_name)
        val speedDesc = describeSpeechRate()

        val currentLangCode = settings.getLanguage()
        val message = if (currentLangCode.startsWith("ru", ignoreCase = true)) {
            "Я всё настроила. Вас зовут: $finalName. Язык общения: $languageName. Скорость речи: $speedDesc. " +
                    "И последнее: когда кнопка зелёная — можно нажимать и говорить. Если она красная — я работаю, нужно немного подождать. " +
                    "Если я понадоблюсь, просто нажмите на зелёную кнопку на экране."
        } else {
            "I've set everything up. I will call you: $finalName. Language: $languageName. Speech rate: $speedDesc. " +
                    "One last thing: when the button is green, you can press and speak. If it is red, I am working, please wait a moment. " +
                    "If you need me, just press the green button on the screen."
        }

        TtsManager.speak(context, message, TextToSpeech.QUEUE_ADD, onDone = {
            scope.launch { EventBus.post(ProcessingStateChanged(false)) }
        })
    }

    private fun autoToggleLanguage(): String {
        val current = settings.getLanguage()
        return if (current.startsWith("ru", true)) "en-US" else "ru-RU"
    }

    private fun applyLanguage(code: String) {
        settings.saveLanguage(code)
        TtsManager.shutdown()
        TtsManager.initialize(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                TtsManager.setLanguage(appContext, code)
            }
        }

        // Force update resources configuration
        val locale = Locale.forLanguageTag(code)
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(appContext.resources.configuration)
        config.setLocale(locale)
        appContext = appContext.createConfigurationContext(config)
        SettingsManager.updateContext(appContext)
        settings.loadSettings()

        // Send a broadcast to notify other components (like the Accessibility Service)
        val intent = Intent(ACTION_LOCALE_CHANGED)
        appContext.sendBroadcast(intent)
        Logger.d("Sent broadcast for locale change.")
    }

    private fun bumpSpeechRate(delta: Float) {
        val cur = settings.getSpeechRate()
        val next = (cur + delta).coerceIn(0.1f, 2.5f)
        val rounded = (round(next * 10f) / 10f)
        settings.saveSpeechRate(rounded)
        TtsManager.setSpeechRate(appContext, rounded)
    }

    private fun describeSpeechRate(): String {
        val rate = settings.getSpeechRate()
        return when {
            rate < 0.9f -> appContext.getString(R.string.speech_rate_slow)
            rate > 1.1f -> appContext.getString(R.string.speech_rate_fast)
            else -> appContext.getString(R.string.speech_rate_normal)
        }
    }

    private fun detectLanguageTarget(text: String): String? {
        val s = text.lowercase(Locale.ROOT)
        val ruHints = listOf("русск", "на русский", "русский", "russian")
        val enHints = listOf("англ", "english", "на англий", "to english")
        val wantRu = ruHints.any { it in s }
        val wantEn = enHints.any { it in s }

        // If both or neither are detected, can't be sure
        if (wantRu == wantEn) return null

        return when {
            wantRu -> "ru-RU"
            wantEn -> "en-US"
            else -> null
        }
    }

    private fun detectSpeedChange(text: String): Command {
        val s = text.lowercase(Locale.ROOT)
        val fasterHints = listOf("faster", "быстрее", "быстрей", "побыстрее")
        val slowerHints = listOf("slower", "медленнее", "помедленней")

        val wantFaster = fasterHints.any { it in s }
        val wantSlower = slowerHints.any { it in s }

        // If both or neither are detected, we can't be sure
        if (wantFaster == wantSlower) return Command.UNKNOWN

        return if (wantFaster) Command.CHANGE_SPEECH_RATE_FASTER else Command.CHANGE_SPEECH_RATE_SLOWER
    }

    private fun gatherDeviceStatus(): DeviceStatus {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isAirplaneMode = Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        val network = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(network)
        val connectionType = when {
            capabilities == null -> "NONE"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
            else -> "UNKNOWN"
        }
        val ringerMode = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "SILENT"
            AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
            else -> "NORMAL"
        }
        val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isLocked = km.isKeyguardLocked

        // --- НОВАЯ СТРОКА: Получаем список приложений ---
        val appsList = getInstalledAppsList(appContext)

        return DeviceStatus(
            is_airplane_mode_on = isAirplaneMode,
            internet_connection_status = connectionType,
            ringer_mode = ringerMode,
            battery_level = batteryPct,
            installed_apps = appsList, // <--- НОВАЯ СТРОКА: Передаем список
            is_keyguard_locked = isLocked
        )
    }

    private fun processActions(actions: List<com.babenko.rescueservice.llm.Action>) {
        scope.launch {
            for (action in actions) {
                try {
                    when (action.type) {
                        "set_goal" -> {
                            val newGoal = action.selector.value
                            currentTaskState = TaskState(goal = newGoal, step = 0)
                            Logger.d("GOAL SET: $newGoal")
                        }
                        "stop_task" -> {
                            currentTaskState = TaskState() // Reset to NONE
                            Logger.d("TASK STOPPED by LLM.")
                        }
                        "click" -> {
                            val selectorMap = mapOf("by" to action.selector.by, "value" to action.selector.value)
                            Log.d("ConvManager", "Posting click event with selector: $selectorMap")
                            EventBus.post(ClickElementEvent(selectorMap))
                            scheduleForceCapture()
                        }
                        "back" -> {
                            Log.d("ConvManager", "Posting back event")
                            EventBus.post(GlobalActionEvent(1)) // Corresponds to AccessibilityService.GLOBAL_ACTION_BACK
                            scheduleForceCapture()
                        }
                        "home" -> {
                            Log.d("ConvManager", "Posting home event")
                            EventBus.post(GlobalActionEvent(2)) // Corresponds to AccessibilityService.GLOBAL_ACTION_HOME
                            scheduleForceCapture()
                        }
                        "highlight" -> {
                            val selectorMap = mapOf("by" to action.selector.by, "value" to action.selector.value)
                            Log.d("ConvManager", "Posting highlight event with selector: $selectorMap")
                            EventBus.post(HighlightElementEvent(selectorMap))
                        }
                        "scroll" -> {
                            val direction = action.selector.value
                            Log.d("ConvManager", "Posting scroll event: $direction")
                            EventBus.post(ScrollEvent(direction))
                            scheduleForceCapture()
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(e, "Failed to process action: $action")
                }
            }
        }
    }

    // NEW: Schedule a forced screen capture to prevent hanging after action
    private fun scheduleForceCapture() {
        scope.launch {
            delay(2000)
            Logger.d("Sending FORCE_CAPTURE broadcast (Pulse)")
            val intent = Intent(RedHelperAccessibilityService.ACTION_FORCE_CAPTURE)
            intent.setPackage(appContext.packageName)
            appContext.sendBroadcast(intent)
        }
    }

    private fun getLocalizedContext(context: Context, languageCode: String): Context {
        val locale = Locale.forLanguageTag(languageCode)
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    private fun getInstalledAppsList(context: Context): String {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        // Получаем список всех установленных приложений, которые можно запустить
        val apps = pm.queryIntentActivities(intent, 0)

        // Превращаем в строку: "WhatsApp, Telegram, YouTube, Sberbank..."
        // Заменяем переносы строк на пробелы, чтобы не ломать JSON
        return apps.joinToString(", ") {
            it.loadLabel(pm).toString().replace("\n", " ")
        }
    }
}