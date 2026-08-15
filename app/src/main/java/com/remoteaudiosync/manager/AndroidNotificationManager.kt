package com.remoteaudiosync.manager

import android.content.Context
import android.provider.Settings
import com.remoteaudiosync.network.ConnectionState
import com.remoteaudiosync.network.ReliableChannel
import com.remoteaudiosync.protocol.NotificationStatePayload
import com.remoteaudiosync.protocol.Packet
import com.remoteaudiosync.protocol.PacketType
import com.remoteaudiosync.service.MediaNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

interface AndroidNotificationManager {
    val activeNotifications: StateFlow<List<NotificationStatePayload>>
    val permissionGranted: StateFlow<Boolean>
    fun start()
    fun stop()
}

class DefaultAndroidNotificationManager(
    private val context: Context,
    private val reliableChannel: ReliableChannel,
    private val stateManager: AudioOwnerStateManager,
    private val coroutineScope: CoroutineScope,
    private val checkPermissions: () -> Boolean = {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        flat != null && flat.contains(context.packageName)
    }
) : AndroidNotificationManager {

    private val _activeNotifications = MutableStateFlow<List<NotificationStatePayload>>(emptyList())
    override val activeNotifications: StateFlow<List<NotificationStatePayload>> = _activeNotifications.asStateFlow()

    private val _permissionGranted = MutableStateFlow(false)
    override val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    private val sentNotificationHashes = mutableMapOf<String, Int>()
    private var connectionStateJob: Job? = null
    private var incomingPacketsJob: Job? = null

    private val notificationServiceListener = object : MediaNotificationListenerService.NotificationListener {
        override fun onNotificationPosted(
            id: String,
            title: String,
            text: String,
            packageName: String,
            appName: String,
            timestamp: Long,
            isOngoing: Boolean,
            category: String
        ) {
            val hash = listOf(title, text, packageName, appName, isOngoing, category).hashCode()
            if (sentNotificationHashes[id] == hash) {
                // Deduplicate: Never resend identical notifications
                return
            }
            sentNotificationHashes[id] = hash

            val existing = _activeNotifications.value.firstOrNull { it.id == id }
            val action = if (existing == null) "ADDED" else "UPDATED"

            val payload = NotificationStatePayload(
                id = id,
                title = title,
                text = text,
                packageName = packageName,
                appName = appName,
                timestamp = timestamp,
                isOngoing = isOngoing,
                action = action
            )

            // Update local list
            val updatedList = _activeNotifications.value.filter { it.id != id } + payload
            _activeNotifications.value = updatedList

            // Send to reliable channel
            sendNotificationPayload(payload)
        }

        override fun onNotificationRemoved(id: String, packageName: String) {
            sentNotificationHashes.remove(id)
            val existing = _activeNotifications.value.firstOrNull { it.id == id } ?: return
            
            val payload = existing.copy(action = "REMOVED")
            
            // Update local list
            _activeNotifications.value = _activeNotifications.value.filter { it.id != id }

            // Send to reliable channel
            sendNotificationPayload(payload)
        }
    }

    override fun start() {
        val granted = checkPermissions()
        _permissionGranted.value = granted
        if (!granted) {
            // publish MISSING_PERMISSION if permission is missing
            sendNotificationPayload(
                NotificationStatePayload(
                    id = "MISSING_PERMISSION",
                    title = "MISSING_PERMISSION",
                    text = "MISSING_PERMISSION",
                    action = "MISSING_PERMISSION"
                )
            )
            return
        }

        MediaNotificationListenerService.listener = notificationServiceListener

        incomingPacketsJob = coroutineScope.launch {
            reliableChannel.incomingPackets.collect { packet ->
                if (packet.packetType == PacketType.NOTIFICATION_STATE && packet.senderId != stateManager.deviceId) {
                    val payload = packet.payload as? NotificationStatePayload
                    if (payload != null) {
                        when (payload.action) {
                            "ADDED", "UPDATED" -> {
                                val updatedList = _activeNotifications.value.filter { it.id != payload.id } + payload
                                _activeNotifications.value = updatedList
                            }
                            "REMOVED" -> {
                                _activeNotifications.value = _activeNotifications.value.filter { it.id != payload.id }
                            }
                        }
                    }
                }
            }
        }

        connectionStateJob = coroutineScope.launch {
            reliableChannel.connectionState.collect { connState ->
                if (connState is ConnectionState.Connected) {
                    // Connection Recovery: restore active notifications
                    _activeNotifications.value.forEach { payload ->
                        sendNotificationPayload(payload)
                    }
                }
            }
        }
    }

    override fun stop() {
        MediaNotificationListenerService.listener = null
        incomingPacketsJob?.cancel()
        incomingPacketsJob = null
        connectionStateJob?.cancel()
        connectionStateJob = null
    }

    private fun sendNotificationPayload(payload: NotificationStatePayload) {
        coroutineScope.launch {
            val packet = Packet(
                version = 1,
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                senderId = stateManager.deviceId,
                receiverId = if (stateManager.deviceId == "android-client") "desktop-server" else "android-client",
                packetType = PacketType.NOTIFICATION_STATE,
                payload = payload
            )
            reliableChannel.send(packet)
        }
    }
}
