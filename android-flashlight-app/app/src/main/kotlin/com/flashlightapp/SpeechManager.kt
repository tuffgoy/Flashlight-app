package com.flashlightapp

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Manages voice recognition in two modes:
 *
 * - **Active**: Runs Android [SpeechRecognizer] continuously, restarting on
 *   timeout or error so the app is always listening.
 * - **Passive**: Uses a low-power [AudioRecord] loop that monitors RMS amplitude.
 *   When ambient sound exceeds [PASSIVE_RMS_THRESHOLD] it triggers one recognition
 *   pass to check for a voice command.
 *
 * Trigger matching is fully configurable via [updateSettings]. TEXT triggers use
 * substring containment; VOICE triggers use Jaro-Winkler similarity against a
 * recorded reference phrase, with a per-trigger threshold (default 80 %).
 */
class SpeechManager(
    private val context: Context,
    private val onCommand: (VoiceCommand) -> Unit
) {

    enum class Mode { ACTIVE, PASSIVE, STOPPED }

    @Volatile private var currentSettings: AppSettings = AppSettings()

    private var currentMode = Mode.STOPPED
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var restartPending = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)
    private var passiveJob: Job? = null

    fun updateSettings(settings: AppSettings) {
        currentSettings = settings
        Log.d(TAG, "Settings updated — language=${settings.language}")
    }

    fun start(mode: Mode) {
        if (mode == currentMode && mode != Mode.STOPPED) return
        stop()
        currentMode = mode
        Log.d(TAG, "Starting in mode: $mode")
        when (mode) {
            Mode.ACTIVE  -> startActiveListening()
            Mode.PASSIVE -> startPassiveMonitoring()
            Mode.STOPPED -> {}
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping SpeechManager")
        restartPending = false
        isListening = false
        passiveJob?.cancel()
        passiveJob = null
        mainHandler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        currentMode = Mode.STOPPED
    }

    // ------------------------------------------------------------------
    // Active mode — continuous SpeechRecognizer
    // ------------------------------------------------------------------

    private fun startActiveListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer not available on this device")
            return
        }
        mainHandler.post { createAndStartRecognizer() }
    }

    private fun createAndStartRecognizer() {
        destroyRecognizer()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }
        beginListening()
    }

    private fun beginListening() {
        if (currentMode == Mode.STOPPED) return
        isListening = true
        restartPending = false
        try {
            recognizer?.startListening(buildIntent())
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}")
            scheduleRestart(RESTART_DELAY_MS)
        }
    }

    private fun buildIntent(silenceMs: Long = 1_500L): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentSettings.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }

    private fun scheduleRestart(delayMs: Long) {
        if (restartPending || currentMode == Mode.STOPPED) return
        restartPending = true
        isListening = false
        mainHandler.postDelayed({
            if (currentMode == Mode.ACTIVE) {
                Log.d(TAG, "Restarting recognizer after ${delayMs}ms")
                createAndStartRecognizer()
            }
        }, delayMs)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { isListening = true }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                for (text in matches) {
                    val cmd = matchCommand(text)
                    if (cmd != null) {
                        Log.i(TAG, "Command '$cmd' from '$text'")
                        onCommand(cmd)
                        break
                    }
                }
            }
            if (currentMode == Mode.ACTIVE) {
                mainHandler.postDelayed({ beginListening() }, ACTIVE_RESTART_AFTER_RESULT_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onError(error: Int) {
            isListening = false
            Log.w(TAG, "Recognition error: ${describeError(error)}")
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Log.e(TAG, "Microphone permission missing — stopping voice control")
                    currentMode = Mode.STOPPED
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    if (currentMode == Mode.ACTIVE) scheduleRestart(RESTART_DELAY_SHORT_MS)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    scheduleRestart(RESTART_DELAY_MS)
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_CLIENT ->
                    if (currentMode == Mode.ACTIVE) {
                        mainHandler.postDelayed({ createAndStartRecognizer() }, RESTART_DELAY_MS)
                    }
                else ->
                    if (currentMode == Mode.ACTIVE) scheduleRestart(RESTART_DELAY_MS)
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { isListening = false }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ------------------------------------------------------------------
    // Passive mode — AudioRecord RMS threshold + one-shot recognition
    // ------------------------------------------------------------------

    private fun startPassiveMonitoring() {
        passiveJob = scope.launch {
            val sampleRate = 16_000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val audio = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed"); return@launch
            }
            audio.startRecording()
            Log.d(TAG, "Passive monitoring started")
            val buffer = ShortArray(bufferSize / 2)
            try {
                while (isActive && currentMode == Mode.PASSIVE) {
                    val read = audio.read(buffer, 0, buffer.size)
                    if (read > 0 && computeRms(buffer, read) > PASSIVE_RMS_THRESHOLD) {
                        audio.stop()
                        mainHandler.post { doPassiveRecognitionPass() }
                        delay(PASSIVE_RECOGNITION_COOLDOWN_MS)
                        if (isActive && currentMode == Mode.PASSIVE) audio.startRecording()
                    }
                    delay(PASSIVE_POLL_INTERVAL_MS)
                }
            } finally {
                audio.release()
                Log.d(TAG, "Passive monitoring stopped")
            }
        }
    }

    private fun doPassiveRecognitionPass() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        destroyRecognizer()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.forEach { text ->
                        val cmd = matchCommand(text); if (cmd != null) { onCommand(cmd); return@forEach }
                    }
                    destroyRecognizer()
                }
                override fun onError(error: Int) { destroyRecognizer() }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(r: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(e: Int, p: Bundle?) {}
            })
        }
        try {
            recognizer?.startListening(buildIntent(silenceMs = 2_000L))
        } catch (e: Exception) {
            Log.e(TAG, "Passive listen failed: ${e.message}"); destroyRecognizer()
        }
    }

    // ------------------------------------------------------------------
    // Command matching — TEXT (substring) + VOICE (Jaro-Winkler)
    // ------------------------------------------------------------------

    private fun matchCommand(raw: String): VoiceCommand? {
        val text = raw.lowercase(Locale.ROOT).trim()
        val s = currentSettings
        return when {
            s.turnOnTriggers.any  { matches(text, it) } -> VoiceCommand.TURN_ON
            s.turnOffTriggers.any { matches(text, it) } -> VoiceCommand.TURN_OFF
            s.shutdownTriggers.any{ matches(text, it) } -> VoiceCommand.SHUTDOWN
            else -> null
        }
    }

    private fun matches(recognized: String, trigger: TriggerWord): Boolean = when (trigger.type) {
        TriggerType.TEXT -> {
            val t = trigger.text.lowercase(Locale.ROOT).trim()
            t.isNotBlank() && recognized.contains(t)
        }
        TriggerType.VOICE -> {
    val ref = trigger.referencePhrase.lowercase(Locale.ROOT).trim()
    if (ref.isBlank()) {
        false
    } else {
        val sim = jaroWinkler(recognized, ref).toFloat()
        Log.d(TAG, "Voice sim '$recognized' vs '$ref' = ${"%.0f".format(sim * 100)}%")
        sim >= trigger.similarityThreshold
     }
        }
    }

    // Jaro similarity
    private fun jaroSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val matchDist = (maxOf(a.length, b.length) / 2) - 1
        val aMatch = BooleanArray(a.length)
        val bMatch = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val lo = maxOf(0, i - matchDist)
            val hi = minOf(i + matchDist + 1, b.length)
            for (j in lo until hi) {
                if (bMatch[j] || a[i] != b[j]) continue
                aMatch[i] = true; bMatch[j] = true; matches++; break
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0; var k = 0
        for (i in a.indices) {
            if (!aMatch[i]) continue
            while (!bMatch[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
        return (matches.toDouble() / a.length +
                matches.toDouble() / b.length +
                (matches - transpositions / 2.0) / matches) / 3.0
    }

    // Jaro-Winkler (prefix bonus p = 0.1, max prefix = 4)
    private fun jaroWinkler(a: String, b: String): Double {
        val jaro   = jaroSimilarity(a, b)
        val prefix = (0 until minOf(4, a.length, b.length)).takeWhile { a[it] == b[it] }.count()
        return jaro + prefix * 0.1 * (1.0 - jaro)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun computeRms(buf: ShortArray, n: Int): Double {
        var sum = 0.0; for (i in 0 until n) sum += buf[i].toDouble() * buf[i]; return Math.sqrt(sum / n)
    }

    private fun destroyRecognizer() {
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null; isListening = false
    }

    private fun describeError(error: Int) = when (error) {
        SpeechRecognizer.ERROR_AUDIO                   -> "audio"
        SpeechRecognizer.ERROR_CLIENT                  -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permissions"
        SpeechRecognizer.ERROR_NETWORK                 -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT         -> "network_timeout"
        SpeechRecognizer.ERROR_NO_MATCH                -> "no_match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY         -> "busy"
        SpeechRecognizer.ERROR_SERVER                  -> "server"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT          -> "speech_timeout"
        else                                           -> "unknown($error)"
    }

    companion object {
        private const val TAG                          = "SpeechManager"
        private const val RESTART_DELAY_MS             = 1_200L
        private const val RESTART_DELAY_SHORT_MS       = 300L
        private const val ACTIVE_RESTART_AFTER_RESULT_MS = 200L
        private const val PASSIVE_RMS_THRESHOLD        = 1_200.0
        private const val PASSIVE_POLL_INTERVAL_MS     = 80L
        private const val PASSIVE_RECOGNITION_COOLDOWN_MS = 4_000L
    }
}

enum class VoiceCommand { TURN_ON, TURN_OFF, SHUTDOWN }
