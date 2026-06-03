package com.flashlightapp

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Wraps [CameraManager] to safely toggle the device torch.
 * Finds the first back-facing camera that has a flash unit.
 */
class FlashlightManager(context: Context) {

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val torchCameraId: String? by lazy { findTorchCameraId() }

    val hasFlash: Boolean get() = torchCameraId != null

    private fun findTorchCameraId(): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(
                    android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE
                ) == true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding torch camera: ${e.message}")
            null
        }
    }

    fun setTorch(enabled: Boolean) {
        val id = torchCameraId ?: run {
            Log.w(TAG, "No torch-capable camera found; ignoring setTorch($enabled)")
            return
        }
        try {
            cameraManager.setTorchMode(id, enabled)
            Log.d(TAG, "Torch ${if (enabled) "ON" else "OFF"}")
        } catch (e: Exception) {
            Log.e(TAG, "setTorchMode failed: ${e.message}")
        }
    }

    fun turnOn() = setTorch(true)
    fun turnOff() = setTorch(false)

    companion object {
        private const val TAG = "FlashlightManager"
    }
}
