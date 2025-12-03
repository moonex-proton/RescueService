package com.babenko.rescueservice.llm

// Expanding the list of commands, making them more specific
enum class Command {
    REPEAT,
    OPEN_SETTINGS,
    CHANGE_NAME,
    CHANGE_LANGUAGE,
    CHANGE_SPEECH_RATE_FASTER,
    CHANGE_SPEECH_RATE_SLOWER,
    // Intent commands for the settings dialog
    INTENT_CHANGE_NAME,
    INTENT_CHANGE_LANGUAGE,
    INTENT_CHANGE_SPEED,
    // Scrolling commands
    SCROLL_DOWN,
    SCROLL_UP,
    // App launch command
    OPEN_APP,
    UNKNOWN
}

// New container class for the command and its associated data (payload)
data class ParsedCommand(val command: Command, val payload: String? = null)

/**
 * A parser object (singleton) for analyzing recognized text and determining the user's command.
 */
object CommandParser {
    // --- Trigger lists -- -
    // IMPORTANT: All triggers must be free of punctuation (hyphens, question marks, apostrophes),
    // because the normalize() function now strips them out.

    private val repeatTriggers = setOf(
        // Original
        "повтори", "repeat", "say it again", "что ты сказал", "не расслышал", "чточто", "ещё раз", // "что-что" -> "чточто"
        // New (RU)
        "скажи ещё раз", "дубль", "я не услышал", "я не услышала", "плохо слышно", "не понял", "не поняла",
        "пропустил", "пропустила", "мимо ушей", "чё ты сказал", "чего сказал", "как ты сказал",
        "как", "чё", "а", "э", "гм", "чего", "что", // Removed '?'
        "повтори погромче", "повтори помедленнее", "повтори почётче", "давай ещё раз", "можно ещё раз", // "по-чётче" -> "почётче"
        "повтори последнее", "повтори сначала",
        // New (EN)
        "say that again", "once more", "once again", "could you repeat", "can you repeat", "tell me again",
        "didnt hear you", "didnt catch that", "missed that", "what did you say", "pardon", "come again", // "didn't" -> "didnt"
        "what was that", "huh", "sorry", "eh", "say it louder", "repeat slower", "more clearly", // Removed '?'
        "one more time", "last bit", "from the beginning"
    )

    private val settingsTriggers = setOf(
        // Original & Variations
        "настройки", "settings", "сетап", "setup", "открой настройки", "open settings",
        "app settings", "configuration", "options", "menu", "open menu", "preferences",
        "change settings", "config",
        // RU
        "давай в настройки", "покажи настройки", "хочу поменять", "помоги настроить", "меню настроек",
        "изменить параметры", "хочу изменить", "давай поправим", "зайди в настройки",
        "открыть настройки", "параметры", "конфигурация", "установки", "опции", "меню"
    )

    // New, more specific triggers
    private val nameChangeTriggers = setOf(
        // Русский
        "сменить имя", "запомни имя", "измени имя", "зови меня", "меня зовут",
        // English
        "change name", "set name", "update name", "my name"
    )

    // --- Language Change Triggers for test ---
    private val toRussianTriggers = setOf(
        // English commands for switching to Russian
        "change language russian", "switch language russian", "set language russian", "speak russian",
        // Russian commands for switching to Russian
        "сменить язык русский", "измени язык русский", "поставь русский", "говори русском"
    )

    private val toEnglishTriggers = setOf(
        // English commands for switching to English
        "change language english", "switch language english", "set language english", "speak english",
        // Russian commands for switching to English
        "сменить язык английский", "измени язык английский", "поставь английский", "говори английском"
    )

    private val speechRateFasterTriggers = setOf(
        // Русский
        "ускорь речь", "скорость речи", "говори быстрее",
        // English
        "speed up speech", "faster speech", "talk faster", "speech speed"
    )

    private val speechRateSlowerTriggers = setOf(
        // Русский
        "замедли речь", "говори медленнее",
        // English
        "speed down speech", "slow speech", "talk slower"
    )

    // --- Scroll Triggers ---
    private val scrollDownTriggers = setOf(
        "прокрути вниз", "листай вниз", "вниз", "ниже",
        "scroll down", "swipe down", "down"
    )

    private val scrollUpTriggers = setOf(
        "прокрути вверх", "листай вверх", "вверх", "выше",
        "scroll up", "swipe up", "up"
    )

    // --- App Launch Triggers ---
    private val openAppTriggers = setOf(
        "открой", "запусти", "открыть", "старт", "включи",
        "open", "launch", "start", "run"
    )

    private val intentNameTriggers = setOf("имя", "name", "change name", "измени имя")
    private val intentLanguageTriggers = setOf("язык", "language")
    private val intentSpeedTriggers = setOf("скорость", "скорость речи", "speed", "speech speed")

    fun parse(text: String): ParsedCommand {
        // Split by the delimiter we use in VoiceSessionService
        val variants = text.split(" ||| ")

        for (variant in variants) {
            val lowercasedText = variant.lowercase()
            // Strict sanitization: remove all punctuation, symbols (*, #), etc.
            val normalizedText = normalize(lowercasedText)

            // 1. Prioritize cross-lingual language change commands
            if (toRussianTriggers.any { normalizedText.equals(it, ignoreCase = true) }) {
                return ParsedCommand(Command.CHANGE_LANGUAGE, "ru-RU")
            }
            if (toEnglishTriggers.any { normalizedText.equals(it, ignoreCase = true) }) {
                return ParsedCommand(Command.CHANGE_LANGUAGE, "en-US")
            }

            // 2. Look for commands with a payload (e.g., name change)
            extractPayload(normalizedText, nameChangeTriggers)?.let { payload ->
                return ParsedCommand(Command.CHANGE_NAME, payload)
            }

            // 2.5 Look for App Launch commands
            extractPayload(normalizedText, openAppTriggers)?.let { payload ->
                return ParsedCommand(Command.OPEN_APP, payload)
            }

            // 3. Look for simple commands (speech rate)
            if (containsTrigger(normalizedText, speechRateFasterTriggers)) return ParsedCommand(Command.CHANGE_SPEECH_RATE_FASTER)
            if (containsTrigger(normalizedText, speechRateSlowerTriggers)) return ParsedCommand(Command.CHANGE_SPEECH_RATE_SLOWER)

            // 3.5 Look for Scroll commands
            if (containsTrigger(normalizedText, scrollDownTriggers)) return ParsedCommand(Command.SCROLL_DOWN)
            if (containsTrigger(normalizedText, scrollUpTriggers)) return ParsedCommand(Command.SCROLL_UP)

            // 4. Check for intent commands for the settings dialog
            if (containsTrigger(normalizedText, intentNameTriggers)) return ParsedCommand(Command.INTENT_CHANGE_NAME)
            if (containsTrigger(normalizedText, intentLanguageTriggers)) return ParsedCommand(Command.INTENT_CHANGE_LANGUAGE)
            if (containsTrigger(normalizedText, intentSpeedTriggers)) return ParsedCommand(Command.INTENT_CHANGE_SPEED)

            if (containsTrigger(normalizedText, repeatTriggers)) return ParsedCommand(Command.REPEAT)

            // 5. Look for the general "settings" command
            if (containsTrigger(normalizedText, settingsTriggers)) return ParsedCommand(Command.OPEN_SETTINGS)
        }

        return ParsedCommand(Command.UNKNOWN)
    }

    private fun normalize(text: String): String {
        // 1. Replace known prepositions/connectors with spaces to keep words separate
        var res = text.replace(" на ", " ").replace(" to ", " ")
        // 2. Remove ANY character that is NOT a letter, number, or whitespace.
        // This strips *, #, ?, !, -, ', etc.
        res = res.replace(Regex("[^\\p{L}\\p{N}\\s]+"), "")
        return res.trim()
    }

    private fun containsTrigger(text: String, triggers: Set<String>): Boolean {
        return triggers.any { text.startsWith(it) }
    }

    private fun extractPayload(text: String, triggers: Set<String>): String? {
        for (trigger in triggers) {
            if (text.startsWith(trigger)) {
                val payload = text.substringAfter(trigger).trim()
                if (payload.isNotEmpty()) {
                    return payload
                }
            }
        }
        return null
    }
}