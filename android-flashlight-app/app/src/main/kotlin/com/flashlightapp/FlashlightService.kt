package com.flashlightapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persistent foreground service that owns the [FlashlightManager] and
 * [SpeechManager] so both can operate while the app is backgrounded.
 *
 * Clients (i.e. [MainActivity]) bind to this service via [LocalBinder] and
 * observe [uiState] to keep the UI in sync.
 */
class FlashlightService : Service() {

    // -------------------------------------------------------------------------
    // Binder
    // -------------------------------------------------------------------------

    inner class LocalBinder : Binder() {
        fun getService(): FlashlightService = this@FlashlightService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    // -------------------------------------------------------------------------
    // State (observable by the bound Activity)
    // -------------------------------------------------------------------------

    data class UiState(
        val flashlightOn: Boolean = false,
        val mode: ListeningMode = ListeningMode.ACTIVE,
        val isShutdown: Boolean = false
    )

    enum class ListeningMode { ACTIVE, PASSIVE, DEACTIVATED }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> get() = _uiState

    // -------------------------------------------------------------------------
    // Resources
    // -------------------------------------------------------------------------

    private lateinit var flashlightManager: FlashlightManager
    private lateinit var speechManager: SpeechManager
    private var wakeLock: PowerManager.WakeLock? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        flashlightManager = FlashlightManager(this)
        speechManager = SpeechManager(this, ::handleVoiceCommand)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stop action received via notification")
                shutdown()
                return START_NOT_STICKY
            }
        }
        Log.d(TAG, "Service started")
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification(_uiState.value.mode))
        speechManager.start(SpeechManager.Mode.ACTIVE)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        speechManager.stop()
        flashlightManager.turnOff()
        releaseWakeLock()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Public control surface (called from Activity / ViewModel)
    // -------------------------------------------------------------------------

    fun setListeningMode(mode: ListeningMode) {
        if (_uiState.value.mode == mode) return
        Log.d(TAG, "Mode change → $mode")
        val speechMode = when (mode) {
            ListeningMode.ACTIVE -> SpeechManager.Mode.ACTIVE
            ListeningMode.PASSIVE -> SpeechManager.Mode.PASSIVE
            ListeningMode.DEACTIVATED -> SpeechManager.Mode.STOPPED
        }
        speechManager.start(speechMode)
        _uiState.value = _uiState.value.copy(mode = mode)
        updateNotification(mode)
    }

    fun setFlashlight(on: Boolean) {
        flashlightManager.setTorch(on)
        _uiState.value = _uiState.value.copy(flashlightOn = on)
    }

    fun shutdown() {
        Log.d(TAG, "Shutting down")
        speechManager.stop()
        flashlightManager.turnOff()
        _uiState.value = _uiState.value.copy(
            flashlightOn = false,
            mode = ListeningMode.DEACTIVATED,
            isShutdown = true
        )
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // -------------------------------------------------------------------------
    // Voice command handler
    // -------------------------------------------------------------------------

    private fun handleVoiceCommand(command: VoiceCommand) {
        Log.i(TAG, "Handling command: $command")
        when (command) {
            VoiceCommand.TURN_ON -> setFlashlight(true)
            VoiceCommand.TURN_OFF -> setFlashlight(false)
            VoiceCommand.SHUTDOWN -> shutdown()
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Flashlight voice control service"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(mode: ListeningMode): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FlashlightService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = when (mode) {
            ListeningMode.ACTIVE -> getString(R.string.notification_text_active)
            ListeningMode.PASSIVE -> getString(R.string.notification_text_passive)
            ListeningMode.DEACTIVATED -> "Deactivated"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openAppIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_action_stop),
                stopIntent
            )
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(mode: ListeningMode) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(mode))
    }

    // -------------------------------------------------------------------------
    // Wake lock
    // -------------------------------------------------------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FlashlightApp::ServiceWakeLock"
        ).also { it.acquire(12 * 60 * 60 * 1000L) } // max 12 h
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "FlashlightService"
        private const val CHANNEL_ID = "flashlight_service_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.flashlightapp.ACTION_STOP"
    }
}
