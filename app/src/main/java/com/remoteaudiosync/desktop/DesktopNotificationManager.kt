package com.remoteaudiosync.desktop

import com.remoteaudiosync.network.ReliableChannel
import com.remoteaudiosync.protocol.NotificationStatePayload
import com.remoteaudiosync.protocol.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DesktopNotificationManager(
    private val reliableChannel: ReliableChannel,
    private val coroutineScope: CoroutineScope
) {
    private val _notifications = MutableStateFlow<List<NotificationStatePayload>>(emptyList())
    val notifications: StateFlow<List<NotificationStatePayload>> = _notifications.asStateFlow()

    private val _isPermissionMissing = MutableStateFlow(false)
    val isPermissionMissing: StateFlow<Boolean> = _isPermissionMissing.asStateFlow()

    /** Hook for surfacing real phone notifications on the native OS (e.g. Windows toasts). */
    var onNativeToast: ((appName: String, title: String, text: String, packageName: String) -> Unit)? = null

    private var packetCollectorJob: Job? = null

    init {
        startListening()
    }

    private fun startListening() {
        packetCollectorJob = coroutineScope.launch {
            reliableChannel.incomingPackets.collect { packet ->
                if (packet.packetType == PacketType.NOTIFICATION_STATE) {
                    val payload = packet.payload as? NotificationStatePayload
                    if (payload != null) {
                        handleIncomingNotification(payload)
                    }
                }
            }
        }
    }

    private fun handleIncomingNotification(payload: NotificationStatePayload) {
        if (payload.action == "MISSING_PERMISSION") {
            _isPermissionMissing.value = true
            return
        }
        _isPermissionMissing.value = false

        when (payload.action) {
            "ADDED", "UPDATED" -> {
                val currentList = _notifications.value
                val existing = currentList.firstOrNull { it.id == payload.id }
                if (existing != null && 
                    existing.title == payload.title && 
                    existing.text == payload.text &&
                    existing.packageName == payload.packageName &&
                    existing.appName == payload.appName &&
                    existing.isOngoing == payload.isOngoing) {
                    // Avoid duplicate notifications: already identical, no change needed
                    return
                }

                // Add or update
                val newList = currentList.filter { it.id != payload.id } + payload
                _notifications.value = newList

                // Surface real phone notifications on the native OS with full context
                val app = if (payload.appName.isNotBlank()) payload.appName else payload.packageName
                onNativeToast?.invoke(app, payload.title, payload.text, payload.packageName)
            }
            "REMOVED" -> {
                val newList = _notifications.value.filter { it.id != payload.id }
                _notifications.value = newList
            }
        }
    }

    fun stop() {
        packetCollectorJob?.cancel()
    }
}
