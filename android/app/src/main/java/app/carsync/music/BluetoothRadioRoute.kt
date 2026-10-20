package app.carsync.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Optional Bluetooth headset/call routing for convoy radio.
 *
 * Does NOT force A2DP media playback into SCO without user consent. SCO call audio
 * is narrower-band than Bluetooth music audio and support depends on head-unit DSP.
 * Must be acquired/released on the app main thread.
 */
class BluetoothRadioRoute(private val context: Context, private val report: (String) -> Unit) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode: Int? = null
    private var callRouteOwned = false
    private var legacySco = false

    fun permissionGranted(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    /** True only if the user enabled this option AND Android reports headset input. */
    fun begin(preferBluetooth: Boolean): Boolean {
        if (!preferBluetooth) return false
        if (!permissionGranted()) {
            report("Bluetooth call audio requires Nearby devices permission on Android 12+.")
            return false
        }
        if (callRouteOwned) return true
        return try {
            // Do not disturb existing audio route unless a BT call endpoint is present.
            val hasHeadset = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            if (!hasHeadset) {
                report("No Bluetooth call microphone detected. Using the normal Android audio route.")
                return false
            }
            val prior = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            var started = false
            if (Build.VERSION.SDK_INT >= 31) {
                val device = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                started = device != null && audioManager.setCommunicationDevice(device)
            } else {
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
                started = true
                legacySco = true
            }
            if (started) {
                previousMode = prior
                callRouteOwned = true
                report(if (legacySco) "Bluetooth call microphone requested (legacy SCO); confirm on car speakers." else
                    "Bluetooth call headset selected for radio; music sound may switch to call quality.")
                true
            } else {
                audioManager.mode = prior
                report("Car Bluetooth call route unavailable. Normal audio output remains selected.")
                false
            }
        } catch (e: Exception) {
            report("Bluetooth route failed: ${e.message.orEmpty()}. Continuing using Android audio.")
            release()
            false
        }
    }

    /**
     * Route the voice capture device explicitly when the Bluetooth call session
     * is active. Do not override the device for normal local music playback.
     */
    fun routeMicrophone(record: android.media.AudioRecord) {
        if (!callRouteOwned || !permissionGranted()) return
        try {
            val input = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            if (input != null && !record.setPreferredDevice(input))
                report("Bluetooth call route selected, but microphone preferred-device selection was rejected.")
        } catch (e: Exception) {
            report("Bluetooth microphone routing unavailable: ${e.message.orEmpty()}")
        }
    }

    fun isCallAudioActive(): Boolean = callRouteOwned

    fun release() {
        if (!callRouteOwned) return
        try {
            if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
            else if (legacySco) {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
        } catch (_: Exception) {}
        try { previousMode?.let { audioManager.mode = it } } catch (_: Exception) {}
        previousMode = null
        legacySco = false
        callRouteOwned = false
    }
}
