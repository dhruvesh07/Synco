package com.remoteaudiosync.ui

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import com.remoteaudiosync.network.ConnectionState
import com.remoteaudiosync.sync.SyncoConnection
import kotlinx.coroutines.flow.StateFlow

fun getActiveAudioDeviceName(context: Context): String {
    try {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "Built-in Speaker"
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var hasBluetooth = false
        var btName = ""
        var wiredName = ""
        var speakerName = ""
        for (device in devices) {
            val name = device.productName?.toString() ?: ""
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                if (name.isNotEmpty()) return name
                hasBluetooth = true
            } else if (device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                wiredName = if (name.isNotEmpty()) name else "Wired Headphones"
            } else if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                speakerName = if (name.isNotEmpty()) name else "Built-in Speaker"
            }
        }
        if (hasBluetooth) return if (btName.isNotEmpty()) btName else "Bluetooth Audio Device"
        if (wiredName.isNotEmpty()) return wiredName
        if (speakerName.isNotEmpty()) return speakerName
    } catch (e: Exception) {
        // Fallback
    }
    return "Built-in Speaker"
}

/**
 * Thin UI-facing wrapper around the process-lifetime [SyncoConnection] singleton.
 *
 * All connection + manager logic lives in [SyncoConnection] (owned by the Application), so it
 * keeps running in the background after the Activity/ViewModel is destroyed (e.g. when the user
 * swipes the app from recents). This class just exposes the same StateFlows and actions the UI
 * already uses, so the Compose screens don't need to change.
 */
class NetworkViewModel(application: Application) : AndroidViewModel(application) {

    val connection: SyncoConnection =
        (application as com.remoteaudiosync.app.RemoteAudioSyncApp).syncoConnection

    val connectionState: StateFlow<ConnectionState> = connection.connectionState
    val isAuthenticated: StateFlow<Boolean> = connection.isAuthenticated

    val profileName: StateFlow<String> = connection.profileName
    val logs: StateFlow<List<String>> = connection.logs
    val pairingStatus: StateFlow<String> = connection.pairingStatus

    val mediaManager = connection.mediaManager
    val artworkManager = connection.artworkManager

    val isAudioOwner: StateFlow<Boolean> = connection.isAudioOwner
    val desktopDeviceInfo: StateFlow<com.remoteaudiosync.protocol.DeviceInfoPayload?> = connection.desktopDeviceInfo
    val activeAudioDevice: StateFlow<String> = connection.activeAudioDevice
    val hasPhonePermission: StateFlow<Boolean> = connection.hasPhonePermission
    val hasNotificationPermission: StateFlow<Boolean> = connection.hasNotificationPermission

    fun updateProfileName(name: String) = connection.updateProfileName(name)
    fun updatePermissionStates() = connection.updatePermissionStates()
    fun connect(ip: String, port: Int) = connection.connect(ip, port)
    fun disconnect() = connection.disconnect()
    fun initiatePairing(pin: String) = connection.initiatePairing(pin)
    fun requestRole(isAudioOwner: Boolean) = connection.requestRole(isAudioOwner)
    fun clearError() = connection.clearError()
    fun clearLogs() = connection.clearLogs()

    override fun onCleared() {
        // Intentionally do NOT stop the managers: the connection must keep running in the
        // background (owned by the Application singleton), independent of this UI ViewModel.
    }
}
