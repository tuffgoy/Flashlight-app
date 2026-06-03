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
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.util.Locale

/**
 * Manages voice recognition in two modes:
 *
 * - **Active**: Runs Android [SpeechRecognizer] continuously; restarts automatically
 *   on timeout or error so the app is always listening.
 * - **Passive**: Uses a low-power [AudioRecord] loop that monitors RMS amplitude.
 *   When ambient sound exceeds [PASSIVE_RMS_THRESHOLD] it triggers one recognition
 *   pass to decide whether a voice command was spoken.
 */
class SpeechManager(
    private val context: Context,
    private val onCommand: (VoiceCommand) -> Unit
) {

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    enum class Mode { ACTIVE, PASSIVE, STOPPED }

    private var currentMode = Mode.STOPPED
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var restartPending = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)
    private var passiveJob: Job? = null

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun start(mode: Mode) {
        if (mode == currentMode && mode != Mode.STOPPED) return
        stop()
        currentMode = mode
        Log.d(TAG, "Starting in mode: $mode")
        when (mode) {
            Mode.ACTIVE -> startActiveListening()
            Mode.PASSIVE -> startPassiveMonitoring()
            Mode.STOPPED -> { /* already stopped */ }
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

    // -------------------------------------------------------------------------
    // Active mode — continuous SpeechRecognizer
    // -------------------------------------------------------------------------

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
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}")
            scheduleRestart(RESTART_DELAY_MS)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (restartPending || currentMode == Mode.STOPPED) return
        restartPending = true
        isListening = false
        mainHandler.postDelayed({
            if (currentMode == Mode.ACTIVE) {
                Log.d(TAG, "Restarting recognizer after delay")
                createAndStartRecognizer()
            }
        }, delayMs)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
            isListening = true
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            Log.d(TAG, "Results: $matches")
            matches?.forEach { text -> parseCommand(text) }
            // Restart immediately so we keep listening
            if (currentMode == Mode.ACTIVE) {
                mainHandler.postDelayed({ beginListening() }, ACTIVE_RESTART_AFTER_RESULT_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            Log.v(TAG, "Partial: $partial")
        }

        override fun onError(error: Int) {
            isListening = false
            val description = speechErrorDescription(error)
            Log.w(TAG, "Recognition error: $description ($error)")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Timeout / no match — restart quickly
                    if (currentMode == Mode.ACTIVE) scheduleRestart(RESTART_DELAY_SHORT_MS)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    scheduleRestart(RESTART_DELAY_MS)
                }
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_CLIENT -> {
                    // Recreate the recognizer object
                    if (currentMode == Mode.ACTIVE) {
                        mainHandler.postDelayed({ createAndStartRecognizer() }, RESTART_DELAY_MS)
                    }
                }
                else -> {
                    if (currentMode == Mode.ACTIVE) scheduleRestart(RESTART_DELAY_MS)
                }
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { isListening = false }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // -------------------------------------------------------------------------
    // Passive mode — AudioRecord RMS threshold + one-shot recognition
    // -------------------------------------------------------------------------

    private fun startPassiveMonitoring() {
        passiveJob = scope.launch {
            val sampleRate = 16_000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return@launch
            }

            audioRecord.startRecording()
            Log.d(TAG, "Passive monitoring started")
            val buffer = ShortArray(bufferSize / 2)

            try {
                while (isActive && currentMode == Mode.PASSIVE) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val rms = computeRms(buffer, read)
                        if (rms > PASSIVE_RMS_THRESHOLD) {
                            Log.d(TAG, "Sound detected (RMS=$rms) — starting one-shot recognition")
                            // Pause AudioRecord and do one recognition pass
                            audioRecord.stop()
                            mainHandler.post { doPassiveRecognitionPass(audioRecord, sampleRate, bufferSize) }
                            // Wait for recognition to complete before resuming
                            delay(PASSIVE_RECOGNITION_COOLDOWN_MS)
                            if (isActive && currentMode == Mode.PASSIVE) {
                                audioRecord.startRecording()
                                Log.d(TAG, "Passive monitoring resumed")
                            }
                        }
                    }
                    delay(PASSIVE_POLL_INTERVAL_MS)
                }
            } finally {
                audioRecord.release()
                Log.d(TAG, "Passive monitoring stopped")
            }
        }
    }

    private fun doPassiveRecognitionPass(
        audioRecord: AudioRecord,
        sampleRate: Int,
        bufferSize: Int
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        destroyRecognizer()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.forEach { parseCommand(it) }
                    destroyRecognizer()
                }
                override fun onError(error: Int) {
                    Log.d(TAG, "Passive recognition error: ${speechErrorDescription(error)}")
                    destroyRecognizer()
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Passive one-shot listen failed: ${e.message}")
            destroyRecognizer()
        }
    }

    private fun computeRms(buffer: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) sum += buffer[i].toDouble() * buffer[i].toDouble()
        return Math.sqrt(sum / count)
    }

    // -------------------------------------------------------------------------
    // Command parsing
    // -------------------------------------------------------------------------

    private fun parseCommand(text: String) {
        val lower = text.lowercase(Locale.getDefault()).trim()
        Log.d(TAG, "Parsing: \"$lower\"")
        val command = when {
            lower.contains("turn on") || lower.contains("flashlight on") ||
            lower.contains("light on") || lower.contains("torch on") -> VoiceCommand.TURN_ON

            lower.contains("turn off") || lower.contains("flashlight off") ||
            lower.contains("light off") || lower.contains("torch off") -> VoiceCommand.TURN_OFF

            lower.contains("deactivate") || lower.contains("shut down") ||
            lower.contains("shutdown") || lower.contains("stop") -> VoiceCommand.SHUTDOWN

            else -> null
        }
        command?.let {
            Log.i(TAG, "Command recognised: $it")
            onCommand(it)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun destroyRecognizer() {
        recognizer?.apply {
            cancel()
            destroy()
        }
        recognizer = null
        isListening = false
    }

    private fun speechErrorDescription(error: Int) = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permissions"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout"
        else -> "unknown($error)"
    }

    companion object {
        private const val TAG = "SpeechManager"
        private const val RESTART_DELAY_MS = 1_200L
        private const val RESTART_DELAY_SHORT_MS = 300L
        private const val ACTIVE_RESTART_AFTER_RESULT_MS = 200L
        private const val PASSIVE_RMS_THRESHOLD = 1_200.0
        private const val PASSIVE_POLL_INTERVAL_MS = 80L
        private const val PASSIVE_RECOGNITION_COOLDOWN_MS = 4_000L
    }
}

enum class VoiceCommand { TURN_ON, TURN_OFF, SHUTDOWN }
