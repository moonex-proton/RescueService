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
    UNKNOWN
}

// New container class for the command and its associated data (payload)
data class ParsedCommand(val command: Command, val payload: String? = null)

/**
 * A parser object (singleton) for analyzing recognized text and determining the user's command.
 */
object CommandParser {
    // --- Trigger lists -- -
    private val repeatTriggers = setOf(
        // Original
        "повтори", "repeat", "say it again", "что ты сказал", "не расслышал", "что-что", "ещё раз",
        // New (RU)
        "скажи ещё раз", "дубль", "я не услышал", "я не услышала", "плохо слышно", "не понял", "не поняла",
        "пропустил", "пропустила", "мимо ушей", "чё ты сказал", "чего сказал", "как ты сказал",
        "как?", "чё?", "а?", "э?", "гм?", "чего?", "что?",
        "повтори погромче", "повтори помедленнее", "повтори по-чётче", "давай ещё раз", "можно ещё раз",
        "повтори последнее", "повтори сначала",
        // New (EN)
        "say that again", "once more", "once again", "could you repeat", "can you repeat", "tell me again",
        "didn't hear you", "didn't catch that", "missed that", "what did you say", "pardon", "come again",
        "what was that", "huh?", "sorry?", "eh?", "say it louder", "repeat slower", "more clearly",
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

    private val intentNameTriggers = setOf("имя", "name", "change name", "измени имя")
    private val intentLanguageTriggers = setOf("язык", "language")
    private val intentSpeedTriggers = setOf("скорость", "скорость речи", "speed", "speech speed")

    fun parse(text: String): ParsedCommand {
        val variants = text.split(" ||| ")

        for (variant in variants) {
            val lowercasedText = variant.lowercase()
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

            // 3. Look for simple commands (speech rate)
            if (containsTrigger(normalizedText, speechRateFasterTriggers)) return ParsedCommand(Command.CHANGE_SPEECH_RATE_FASTER)
            if (containsTrigger(normalizedText, speechRateSlowerTriggers)) return ParsedCommand(Command.CHANGE_SPEECH_RATE_SLOWER)

            // 4. Check for intent commands for the settings dialog
            if (containsTrigger(lowercasedText, intentNameTriggers)) return ParsedCommand(Command.INTENT_CHANGE_NAME)
            if (containsTrigger(lowercasedText, intentLanguageTriggers)) return ParsedCommand(Command.INTENT_CHANGE_LANGUAGE)
            if (containsTrigger(lowercasedText, intentSpeedTriggers)) return ParsedCommand(Command.INTENT_CHANGE_SPEED)

            if (containsTrigger(lowercasedText, repeatTriggers)) return ParsedCommand(Command.REPEAT)

            // 5. Look for the general "settings" command
            if (containsTrigger(lowercasedText, settingsTriggers)) return ParsedCommand(Command.OPEN_SETTINGS)
        }

        return ParsedCommand(Command.UNKNOWN)
    }

    private fun normalize(text: String): String {
        return text.replace(" на ", " ").replace(" to ", " ")
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