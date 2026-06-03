package com.flashlightapp

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Owns the service binding lifecycle so the Activity can be destroyed and
 * recreated (rotation, etc.) without losing the service connection.
 *
 * Also holds [settings] state and exposes [recordReferencePhrase] so the
 * Settings screen can capture a voice sample. Must be called on the main thread.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TriggerRepository(application)

    private val _uiState = MutableStateFlow(FlashlightService.UiState())
    val uiState: StateFlow<FlashlightService.UiState> get() = _uiState

    private val _settings = MutableStateFlow(repository.loadSettings())
    val settings: StateFlow<AppSettings> get() = _settings

    private var service: FlashlightService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, iBinder: IBinder?) {
            val binder = iBinder as FlashlightService.LocalBinder
            service = binder.getService()
            bound = true
            Log.d(TAG, "Service connected")
            service!!.updateSettings(_settings.value)
            viewModelScope.launch {
                service!!.uiState.collectLatest { state -> _uiState.value = state }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; bound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    fun bindToService(context: Context) {
        if (bound) return
        val intent = Intent(context, FlashlightService::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbindFromService(context: Context) {
        if (!bound) return
        context.unbindService(connection); bound = false
    }

    fun saveSettings(settings: AppSettings) {
        _settings.value = settings
        service?.updateSettings(settings)
    }

    fun setListeningMode(mode: FlashlightService.ListeningMode) {
        service?.setListeningMode(mode) ?: Log.w(TAG, "setListeningMode: service not bound")
    }

    fun toggleFlashlight() {
        service?.setFlashlight(!_uiState.value.flashlightOn)
            ?: Log.w(TAG, "toggleFlashlight: service not bound")
    }

    fun shutdown(context: Context) {
        service?.shutdown(); unbindFromService(context)
    }

    /**
     * Records a single voice sample for use as a VOICE trigger reference phrase.
     * Temporarily pauses the service recognizer to avoid resource conflicts.
     * [onResult] is invoked on the main thread with the recognised text,
     * or an empty string on failure.
     *
     * **Must be called on the main thread.**
     */
    fun recordReferencePhrase(context: Context, onResult: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onResult(""); return
        }
        val previousMode = _uiState.value.mode
        service?.setListeningMode(FlashlightService.ListeningMode.DEACTIVATED)

        Handler(Looper.getMainLooper()).post {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    recognizer.destroy()
                    service?.setListeningMode(previousMode)
                    onResult(text)
                }
                override fun onError(error: Int) {
                    Log.w(TAG, "recordReferencePhrase error: $error")
                    recognizer.destroy()
                    service?.setListeningMode(previousMode)
                    onResult("")
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, _settings.value.language)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
            }
            try {
                recognizer.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "recordReferencePhrase failed: ${e.message}")
                recognizer.destroy()
                service?.setListeningMode(previousMode)
                onResult("")
            }
        }
    }

    override fun onCleared() { super.onCleared(); Log.d(TAG, "ViewModel cleared") }

    companion object { private const val TAG = "MainViewModel" }
}
