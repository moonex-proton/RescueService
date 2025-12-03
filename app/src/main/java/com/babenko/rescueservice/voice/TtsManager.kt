package com.babenko.rescueservice.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.babenko.rescueservice.core.Logger
import com.babenko.rescueservice.data.SettingsManager
import java.util.Locale
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

object TtsManager : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val pending: ArrayDeque<Triple<String, Int, (() -> Unit)?>> = ArrayDeque()
    private val utteranceCallbacks = ConcurrentHashMap<String, () -> Unit>()
    internal var lastSpokenMessage: String? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus: Boolean = false

    // List of initialization-time callbacks
    private val onInitListeners = mutableListOf<(Int) -> Unit>()

    private fun ensureAudioManager(ctx: Context) {
        if (audioManager == null) {
            audioManager = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest == null) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .setAcceptsDelayedFocusGain(true)
                .build()
        }
    }

    private fun requestFocus(ctx: Context): Boolean {
        ensureAudioManager(ctx)
        val am = audioManager ?: return true
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            am.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                { },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        } == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasFocus = granted
        if (granted) {
            Logger.d("TtsManager: audio focus GRANTED")
        } else {
            Logger.d("TtsManager: audio focus NOT granted, will try speaking anyway")
        }
        return granted
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        if (!hasFocus) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
            Logger.d("TtsManager: audio focus ABANDONED")
        } catch (_: Exception) {
        } finally {
            hasFocus = false
        }
    }

    fun initialize(context: Context, onInit: ((Int) -> Unit)? = null) {
        onInit?.let { onInitListeners.add(it) }
        if (tts != null && isInitialized) {
            onInitListeners.forEach { it.invoke(TextToSpeech.SUCCESS) }
            onInitListeners.clear()
            return
        }
        if (tts != null && !isInitialized) {
            // Initialization is in progress, listener is already added.
            return
        }
        appContext = context.applicationContext
        ensureAudioManager(appContext!!)
        try {
            tts = TextToSpeech(appContext, this, "com.google.android.tts")
        } catch (e: Exception) {
            Logger.e(e, "Failed to create TextToSpeech")
        }
    }

    override fun onInit(status: Int) {
        onInitListeners.forEach { it.invoke(status) }
        onInitListeners.clear()

        if (status != TextToSpeech.SUCCESS) {
            Logger.d("TextToSpeech initialization failed with status: $status")
            pending.clear()
            return
        }
        isInitialized = true
        Logger.d("TextToSpeech initialized successfully.")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                handler.post {
                    abandonFocus()
                    utteranceId?.let {
                        utteranceCallbacks.remove(it)?.invoke()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post {
                    abandonFocus()
                    utteranceId?.let { utteranceCallbacks.remove(it) }
                }
            }
        })
        tryApplySettingsSafely()
        while (pending.isNotEmpty()) {
            val (text, mode, onDone) = pending.removeFirst()
            internalSpeak(text, mode, onDone)
        }
    }

    private fun tryApplySettingsSafely() {
        val ctx = appContext ?: return
        val settings = SettingsManager.getInstance(ctx)
        val langCode = settings.getLanguage()
        val desired = if (langCode.startsWith("ru", ignoreCase = true)) Locale("ru", "RU") else Locale.forLanguageTag(langCode)
        var res = tts?.setLanguage(desired)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            val ru = Locale("ru", "RU")
            res = tts?.setLanguage(ru)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.US
                Logger.d("TTS fallback to en-US")
            } else {
                Logger.d("TTS language set to ru-RU (fallback)")
            }
        } else {
            Logger.d("TTS language set to: $langCode")
        }
        val rate = settings.getSpeechRate()
        tts?.setSpeechRate(rate)
        Logger.d("TTS speech rate set to: $rate")
        try {
            val locale = tts?.language
            val male = tts?.voices?.firstOrNull { v ->
                v.locale == locale && v.name.contains("male", ignoreCase = true)
            }
            if (male != null) {
                tts?.voice = male
                Logger.d("TTS male voice set: ${male.name}")
            }
        } catch (_: Exception) { }
    }

    // --- Added sanitation logic ---
    private fun sanitizeText(text: String): String {
        // Удаляем спецсимволы Markdown: *, #, `, ~, _
        // Заменяем на пробел, чтобы не склеивать слова
        return text.replace(Regex("[*#`~_]+"), " ").trim()
    }

    fun speak(context: Context, text: String, queueMode: Int, onDone: (() -> Unit)? = null) {
        val cleanText = sanitizeText(text)
        lastSpokenMessage = cleanText
        if (tts == null) initialize(context)
        if (!isInitialized) {
            pending.addLast(Triple(cleanText, queueMode, onDone))
            return
        }
        requestFocus(context)
        internalSpeak(cleanText, queueMode, onDone)
    }

    private fun internalSpeak(text: String, queueMode: Int, onDone: (() -> Unit)? = null) {
        val utteranceId = text.hashCode().toString() + System.currentTimeMillis()
        onDone?.let { utteranceCallbacks[utteranceId] = it }
        val result = tts?.speak(text, queueMode, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            Logger.e(
                Exception("TTS speak failed. Engine might be disconnected."),
                "Re-initializing TTS and re-queuing message: '$text'"
            )
            utteranceCallbacks.remove(utteranceId)
            shutdown()
            pending.addLast(Triple(text, queueMode, onDone))
            appContext?.let { initialize(it) }
        } else {
            Logger.d("TTS speaking: '$text' (queue=$queueMode) id=$utteranceId")
        }
    }

    fun setLanguage(context: Context, languageCode: String) {
        if (tts == null) initialize(context)
        if (!isInitialized) return
        val result = tts?.setLanguage(Locale.forLanguageTag(languageCode))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Logger.d("Attempted to set unsupported language: $languageCode")
            return
        }
        SettingsManager.getInstance(context).saveLanguage(languageCode)
        Logger.d("TTS language changed and saved: $languageCode")
    }

    fun setSpeechRate(context: Context, rate: Float) {
        if (tts == null) initialize(context)
        if (!isInitialized) return
        val clamped = rate.coerceIn(0.1f, 3.0f)
        val ok = tts?.setSpeechRate(clamped)
        if (ok == TextToSpeech.SUCCESS) {
            SettingsManager.getInstance(context).saveSpeechRate(clamped)
            Logger.d("TTS rate changed and saved: $clamped")
        }
    }

    fun repeatLastMessage(context: Context) {
        val msg = lastSpokenMessage ?: return
        speak(context, msg, TextToSpeech.QUEUE_FLUSH)
    }

    fun shutdown() {
        abandonFocus()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        pending.clear()
        utteranceCallbacks.clear()
        lastSpokenMessage = null
    }
}