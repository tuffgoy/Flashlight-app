package com.flashlightapp

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
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
 * Mirrors [FlashlightService.UiState] into its own [uiState] so the
 * Compose UI observes a single source of truth.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(FlashlightService.UiState())
    val uiState: StateFlow<FlashlightService.UiState> get() = _uiState

    private var service: FlashlightService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, iBinder: IBinder?) {
            val binder = iBinder as FlashlightService.LocalBinder
            service = binder.getService()
            bound = true
            Log.d(TAG, "Service connected")
            // Mirror service state into ViewModel's flow
            viewModelScope.launch {
                service!!.uiState.collectLatest { state ->
                    _uiState.value = state
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    // -------------------------------------------------------------------------
    // Binding
    // -------------------------------------------------------------------------

    fun bindToService(context: Context) {
        if (bound) return
        val intent = Intent(context, FlashlightService::class.java)
        // Start the service first so it outlives the Activity
        context.startForegroundService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbindFromService(context: Context) {
        if (!bound) return
        context.unbindService(connection)
        bound = false
    }

    // -------------------------------------------------------------------------
    // Control surface (delegates to service)
    // -------------------------------------------------------------------------

    fun setListeningMode(mode: FlashlightService.ListeningMode) {
        service?.setListeningMode(mode) ?: Log.w(TAG, "setListeningMode called but service not bound")
    }

    fun toggleFlashlight() {
        val on = !_uiState.value.flashlightOn
        service?.setFlashlight(on) ?: Log.w(TAG, "toggleFlashlight called but service not bound")
    }

    fun shutdown(context: Context) {
        service?.shutdown()
        unbindFromService(context)
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        // ViewModel is cleared when Activity is finished for good (not rotation).
        // We intentionally do NOT stop the service here — only explicit "Shut Down"
        // button / voice command should kill it.
        Log.d(TAG, "ViewModel cleared")
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
