package com.remoteaudiosync.sync

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.remoteaudiosync.manager.PairingManager
import com.remoteaudiosync.manager.PairingResult
import com.remoteaudiosync.manager.TrustedDeviceManager
import com.remoteaudiosync.network.ConnectionState
import com.remoteaudiosync.network.WebSocketClient
import com.remoteaudiosync.service.SyncoForegroundService
import com.remoteaudiosync.ui.getActiveAudioDeviceName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.remoteaudiosync.protocol.PacketType

/**
 * Process-lifetime owner of the entire Synco connection + manager graph.
 *
 * Unlike the previous Activity-scoped NetworkViewModel, this object lives as long as the
 * Application process and runs on its own [CoroutineScope] that is NOT tied to any Activity.
 * That means the WebSocket, pairing, media, call, notification and bluetooth-ownership managers
 * all keep running even after the user swipes the app from recents (as long as the process stays
 * alive via the foreground service). Combined with the auto re-pair flow, the link self-heals.
 */
class SyncoConnection(private val application: Application) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val webSocketClient = WebSocketClient()
    private val cryptoManager = com.remoteaudiosync.crypto.CryptoManager()
    val reliableChannel = com.remoteaudiosync.network.ReliableChannel(webSocketClient, cryptoManager, scope)

    val connectionState: StateFlow<ConnectionState> = reliableChannel.connectionState
    val isAuthenticated: StateFlow<Boolean> = reliableChannel.isAuthenticated

    private val trustedDeviceManager = TrustedDeviceManager(application)
    private val identityKeyStore = com.remoteaudiosync.crypto.IdentityKeyStore(application)
    private val pairingManager = PairingManager(reliableChannel, trustedDeviceManager, identityKeyStore, cryptoManager)

    private val settingsPrefs = application.getSharedPreferences("synco_settings", Context.MODE_PRIVATE)

    // Persisted last-known desktop server so the foreground service can transparently reconnect
    // even after the process was fully restarted.
    private var lastServerIp: String?
        get() = settingsPrefs.getString("last_server_ip", null)
        set(value) { settingsPrefs.edit().putString("last_server_ip", value).apply() }
    private var lastServerPort: Int
        get() = settingsPrefs.getInt("last_server_port", 8765)
        set(value) { settingsPrefs.edit().putInt("last_server_port", value).apply() }
    private var userDisconnected = false

    val artworkCache = com.remoteaudiosync.artwork.ArtworkCache(application)
    val artworkManager = com.remoteaudiosync.artwork.ArtworkManager(reliableChannel, artworkCache, null, scope)
    val mediaManager = com.remoteaudiosync.manager.MediaManager(application, reliableChannel, scope, artworkCache)

    val audioOwnerStateManager = com.remoteaudiosync.manager.DefaultAudioOwnerStateManager("android-client")
    val bluetoothDeviceMonitor = com.remoteaudiosync.manager.DefaultBluetoothDeviceMonitor(application)
    val bluetoothOwnershipManager = com.remoteaudiosync.manager.DefaultBluetoothOwnershipManager(
        deviceMonitor = bluetoothDeviceMonitor,
        stateManager = audioOwnerStateManager,
        reliableChannel = reliableChannel,
        coroutineScope = scope,
        onRoleChanged = { role ->
            val isOwner = role == com.remoteaudiosync.manager.AudioRole.ACTIVE_AUDIO_OWNER
            _isAudioOwner.value = isOwner
            mediaManager.setRole(isOwner)
        }
    )

    val callManager = com.remoteaudiosync.manager.DefaultAndroidCallManager(
        context = application,
        reliableChannel = reliableChannel,
        stateManager = audioOwnerStateManager,
        coroutineScope = scope
    )
    val notificationManager = com.remoteaudiosync.manager.DefaultAndroidNotificationManager(
        context = application,
        reliableChannel = reliableChannel,
        stateManager = audioOwnerStateManager,
        coroutineScope = scope
    )

    private val _isAudioOwner = MutableStateFlow(false)
    val isAudioOwner: StateFlow<Boolean> = _isAudioOwner.asStateFlow()

    private val _desktopDeviceInfo = MutableStateFlow<com.remoteaudiosync.protocol.DeviceInfoPayload?>(null)
    val desktopDeviceInfo: StateFlow<com.remoteaudiosync.protocol.DeviceInfoPayload?> = _desktopDeviceInfo.asStateFlow()

    private val _activeAudioDevice = MutableStateFlow("Built-in Speaker")
    val activeAudioDevice: StateFlow<String> = _activeAudioDevice.asStateFlow()

    private val _hasPhonePermission = MutableStateFlow(false)
    val hasPhonePermission: StateFlow<Boolean> = _hasPhonePermission.asStateFlow()

    val hasNotificationPermission: StateFlow<Boolean> = mediaManager.hasNotificationPermission

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _pairingStatus = MutableStateFlow<String>("")
    val pairingStatus: StateFlow<String> = _pairingStatus.asStateFlow()

    private val _profileName = MutableStateFlow(settingsPrefs.getString("profile_name", "") ?: "")
    val profileName: StateFlow<String> = _profileName.asStateFlow()

    private var started = false

    fun updateProfileName(name: String) {
        _profileName.value = name
        settingsPrefs.edit().putString("profile_name", name).apply()
    }

    fun updatePermissionStates() {
        val phoneGranted = application.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val answerGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            application.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        _hasPhonePermission.value = phoneGranted && answerGranted
        mediaManager.checkPermission()
    }

    /** Start the collectors. Safe to call multiple times (idempotent). */
    fun start() {
        if (started) return
        started = true
        updatePermissionStates()
        bluetoothDeviceMonitor.startMonitoring()

        scope.launch {
            launch {
                connectionState.collect { state ->
                    val active = state is ConnectionState.Connected ||
                            state is ConnectionState.Connecting ||
                            state is ConnectionState.Reconnecting ||
                            state is ConnectionState.WaitingForAck
                    try {
                        if (active) {
                            val intent = Intent(application, SyncoForegroundService::class.java).apply {
                                action = SyncoForegroundService.ACTION_START
                            }
                            application.startForegroundService(intent)
                        } else {
                            val intent = Intent(application, SyncoForegroundService::class.java).apply {
                                action = SyncoForegroundService.ACTION_STOP
                            }
                            application.startService(intent)
                        }
                    } catch (e: Exception) {
                        // Suppress background start exceptions
                    }
                }
            }
            launch {
                webSocketClient.logs.collect { log ->
                    _logs.update { (it + "WS: $log").takeLast(100) }
                }
            }
            launch {
                reliableChannel.logs.collect { log ->
                    _logs.update { (it + "RC: $log").takeLast(100) }
                }
            }
            launch {
                reliableChannel.isAuthenticated.collect { authenticated ->
                    if (authenticated) {
                        bluetoothOwnershipManager.start()
                        callManager.start()
                        notificationManager.start()
                    } else {
                        bluetoothOwnershipManager.stop()
                        callManager.stop()
                        notificationManager.stop()
                        _desktopDeviceInfo.value = null
                    }
                }
            }
            launch {
                webSocketClient.connectionState.collect { state ->
                    if (state is ConnectionState.Connected) {
                        val storedPin = trustedDeviceManager.getPairPin()
                        if (storedPin != null && !reliableChannel.isAuthenticated.value) {
                            _pairingStatus.value = "Re-pairing..."
                            when (val result = pairingManager.autoReconnect(storedPin)) {
                                is PairingResult.Success -> {
                                    reliableChannel.setAuthenticated(true)
                                    _pairingStatus.value = "Paired"
                                }
                                is PairingResult.Failed -> {
                                    reliableChannel.setAuthenticated(false)
                                    _pairingStatus.value = "Re-pair failed: ${result.reason}"
                                }
                            }
                        }
                    } else if ((state is ConnectionState.Disconnected || state is ConnectionState.Failed)
                        && !userDisconnected && trustedDeviceManager.hasStoredPin()) {
                        val ip = lastServerIp
                        if (ip != null) {
                            _pairingStatus.value = "Reconnecting..."
                            delay(3000L)
                            reliableChannel.connect(ip, lastServerPort)
                        }
                    }
                }
            }
            launch {
                reliableChannel.incomingPackets.collect { packet ->
                    if (packet.packetType == PacketType.DEVICE_INFO) {
                        val payload = packet.payload as? com.remoteaudiosync.protocol.DeviceInfoPayload
                        if (payload != null) {
                            _desktopDeviceInfo.value = payload
                        }
                    }
                }
            }
            launch {
                while (true) {
                    _activeAudioDevice.value = getActiveAudioDeviceName(application)
                    updatePermissionStates()
                    delay(2000)
                }
            }
        }
    }

    fun connect(ip: String, port: Int) {
        if (ip.isNotBlank()) {
            userDisconnected = false
            lastServerIp = ip
            lastServerPort = port
            reliableChannel.connect(ip, port)
        }
    }

    /** Reconnect to the last known server (used by the foreground service after process restart). */
    fun reconnectLastServer() {
        val ip = lastServerIp
        if (ip != null && !userDisconnected) {
            reliableChannel.connect(ip, lastServerPort)
        }
    }

    fun disconnect() {
        userDisconnected = true
        reliableChannel.disconnect()
        _pairingStatus.value = ""
    }

    fun initiatePairing(pin: String) {
        scope.launch {
            _pairingStatus.value = "Pairing..."
            when (val result = pairingManager.initiatePairing(pin)) {
                is PairingResult.Success -> {
                    reliableChannel.setAuthenticated(true)
                    _pairingStatus.value = "Paired"
                }
                is PairingResult.Failed -> {
                    reliableChannel.setAuthenticated(false)
                    _pairingStatus.value = "Failed: ${result.reason}"
                }
            }
        }
    }

    fun requestRole(isAudioOwner: Boolean) {
        scope.launch {
            val targetRole = if (isAudioOwner) {
                com.remoteaudiosync.manager.AudioRole.ACTIVE_AUDIO_OWNER
            } else {
                com.remoteaudiosync.manager.AudioRole.REMOTE_CONTROLLER
            }
            bluetoothOwnershipManager.switchManager.initiateSwitch(targetRole)
        }
    }

    fun clearError() {
        webSocketClient.clearError()
        _pairingStatus.value = ""
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
