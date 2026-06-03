package com.flashlightapp

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class TriggerRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): AppSettings = AppSettings(
        language = prefs.getString(KEY_LANGUAGE, "en-US") ?: "en-US",
        turnOnTriggers  = loadTriggers(KEY_TURN_ON)  ?: defaultTurnOnTriggers(),
        turnOffTriggers = loadTriggers(KEY_TURN_OFF) ?: defaultTurnOffTriggers(),
        shutdownTriggers = loadTriggers(KEY_SHUTDOWN) ?: defaultShutdownTriggers()
    )

    fun saveSettings(settings: AppSettings) {
        prefs.edit().apply {
            putString(KEY_LANGUAGE, settings.language)
            putString(KEY_TURN_ON,  encodeTriggers(settings.turnOnTriggers))
            putString(KEY_TURN_OFF, encodeTriggers(settings.turnOffTriggers))
            putString(KEY_SHUTDOWN, encodeTriggers(settings.shutdownTriggers))
        }.apply()
    }

    private fun encodeTriggers(list: List<TriggerWord>): String =
        JSONArray().also { arr ->
            list.forEach { t ->
                arr.put(JSONObject().apply {
                    put("id",                 t.id)
                    put("type",               t.type.name)
                    put("text",               t.text)
                    put("referencePhrase",    t.referencePhrase)
                    put("similarityThreshold", t.similarityThreshold.toDouble())
                })
            }
        }.toString()

    private fun loadTriggers(key: String): List<TriggerWord>? {
        val json = prefs.getString(key, null) ?: return null
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TriggerWord(
                    id                  = o.optString("id", UUID.randomUUID().toString()),
                    type                = TriggerType.valueOf(o.getString("type")),
                    text                = o.optString("text", ""),
                    referencePhrase     = o.optString("referencePhrase", ""),
                    similarityThreshold = o.optDouble("similarityThreshold", 0.80).toFloat()
                )
            }
        }.getOrNull()
    }

    companion object {
        private const val PREFS_NAME  = "flashlight_settings"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_TURN_ON  = "turn_on_triggers"
        private const val KEY_TURN_OFF = "turn_off_triggers"
        private const val KEY_SHUTDOWN = "shutdown_triggers"
    }
}
