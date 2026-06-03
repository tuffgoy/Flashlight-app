package com.flashlightapp

import java.util.UUID

enum class TriggerType { TEXT, VOICE }

data class TriggerWord(
    val id: String = UUID.randomUUID().toString(),
    val type: TriggerType = TriggerType.TEXT,
    val text: String = "",
    val referencePhrase: String = "",
    val similarityThreshold: Float = 0.80f
)

data class AppSettings(
    val language: String = "en-US",
    val turnOnTriggers: List<TriggerWord> = defaultTurnOnTriggers(),
    val turnOffTriggers: List<TriggerWord> = defaultTurnOffTriggers(),
    val shutdownTriggers: List<TriggerWord> = defaultShutdownTriggers()
)

fun defaultTurnOnTriggers(): List<TriggerWord> = listOf(
    TriggerWord(type = TriggerType.TEXT, text = "turn on"),
    TriggerWord(type = TriggerType.TEXT, text = "flashlight on"),
    TriggerWord(type = TriggerType.TEXT, text = "light on")
)

fun defaultTurnOffTriggers(): List<TriggerWord> = listOf(
    TriggerWord(type = TriggerType.TEXT, text = "turn off"),
    TriggerWord(type = TriggerType.TEXT, text = "flashlight off"),
    TriggerWord(type = TriggerType.TEXT, text = "light off")
)

fun defaultShutdownTriggers(): List<TriggerWord> = listOf(
    TriggerWord(type = TriggerType.TEXT, text = "shut down"),
    TriggerWord(type = TriggerType.TEXT, text = "deactivate"),
    TriggerWord(type = TriggerType.TEXT, text = "stop")
)

val SUPPORTED_LANGUAGES: List<Pair<String, String>> = listOf(
    "en-US" to "English (US)",
    "en-GB" to "English (UK)",
    "es-ES" to "Spanish",
    "fr-FR" to "French",
    "de-DE" to "German",
    "it-IT" to "Italian",
    "pt-BR" to "Portuguese (Brazil)",
    "nl-NL" to "Dutch",
    "ru-RU" to "Russian",
    "ja-JP" to "Japanese",
    "ko-KR" to "Korean",
    "zh-CN" to "Chinese (Simplified)",
    "ar-SA" to "Arabic",
    "hi-IN" to "Hindi"
)
