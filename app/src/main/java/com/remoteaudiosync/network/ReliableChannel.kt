package com.remoteaudiosync.network

import com.remoteaudiosync.crypto.CryptoManager
import com.remoteaudiosync.protocol.Packet
import com.remoteaudiosync.protocol.PacketCodec
import com.remoteaudiosync.protocol.PacketType
import com.remoteaudiosync.protocol.PacketValidator
import com.remoteaudiosync.protocol.ProtocolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

open class ReliableChannel(
    private val webSocketClient: WebSocketClient,
    private val cryptoManager: CryptoManager,
    private val coroutineScope: CoroutineScope,
    var timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    open val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    open val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<Packet>(extraBufferCapacity = 100)
    open val incomingPackets = _incomingPackets.asSharedFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val logs = _logs.asSharedFlow()

    private val ackRequiredTypes = setOf(
        PacketType.MEDIA_COMMAND,
        PacketType.CALL_COMMAND,
        PacketType.ROLE_CHANGE_REQUEST,
        PacketType.ARTWORK_REQUEST
    )

    private val doNotAckTypes = setOf(
        PacketType.HEARTBEAT,
        PacketType.PING,
        PacketType.PONG,
        PacketType.PAIR_REQUEST,
        PacketType.PAIR_RESPONSE,
        PacketType.AUTH_REQUEST,
        PacketType.AUTH_SUCCESS,
        PacketType.ERROR,
        PacketType.MEDIA_STATE,
        PacketType.CALL_STATE,
        PacketType.NOTIFICATION_STATE,
        PacketType.BATTERY_STATE,
        PacketType.DEVICE_INFO
    )

    private val pendingAcks = ConcurrentHashMap<String, Job>()

    private var heartbeatJob: Job? = null
    private var heartbeatMonitorJob: Job? = null
    private var lastHeartbeatReceived = 0L

    private var currentIp: String? = null
    private var currentPort: Int? = null

    fun setAuthenticated(auth: Boolean) {
        _isAuthenticated.value = auth
        if (auth) {
            startHeartbeat()
        } else {
            stopHeartbeat()
            cryptoManager.clearSessionKey()
        }
    }

    init {
        coroutineScope.launch {
            webSocketClient.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        _connectionState.value = ConnectionState.Connected
                    }
                    is ConnectionState.Disconnected -> {
                        if (_connectionState.value !is ConnectionState.Lost && _connectionState.value !is ConnectionState.Reconnecting) {
                            _connectionState.value = ConnectionState.Disconnected
                        }
                        setAuthenticated(false)
                    }
                    is ConnectionState.Failed -> {
                        _connectionState.value = state
                        setAuthenticated(false)
                    }
                    else -> _connectionState.value = state
                }
            }
        }

        coroutineScope.launch {
            webSocketClient.messages.collect { message ->
                try {
                    handleIncomingMessage(message)
                } catch (e: Exception) {
                    log("Exception handling message: ${e.message}")
                }
            }
        }
    }

    fun connect(ip: String, port: Int) {
        currentIp = ip
        currentPort = port
        webSocketClient.connect(ip, port)
    }

    fun disconnect() {
        webSocketClient.disconnect()
    }

    private fun handleIncomingMessage(message: String) {
        val deserializeResult = PacketCodec.deserialize(message)
        if (deserializeResult !is ProtocolResult.Success) {
            log("Malformed packet received")
            return
        }

        var packet = deserializeResult.data

        val validateResult = PacketValidator.validate(packet)
        if (validateResult is ProtocolResult.Failure) {
            log("Packet validation failed: ${validateResult.error.message}")
            return
        }
        packet = (validateResult as ProtocolResult.Success).data

        if (packet.ciphertext != null) {
            val decryptResult = PacketCodec.decryptPayload(packet, cryptoManager)
            if (decryptResult is ProtocolResult.Success) {
                packet = decryptResult.data
            } else {
                log("Decryption failed")
                return
            }
        }

        when (packet.packetType) {
            PacketType.HEARTBEAT -> {
                lastHeartbeatReceived = timeProvider()
            }
            PacketType.ACK -> {
                val ackId = (packet.payload as? com.remoteaudiosync.protocol.AckPayload)?.originalPacketId
                if (ackId != null) {
                    val job = pendingAcks.remove(ackId)
                    if (job != null) {
                        job.cancel()
                        log("RX ACK")
                        if (_connectionState.value is ConnectionState.WaitingForAck) {
                            _connectionState.value = ConnectionState.Connected
                        }
                    } else {
                        log("Duplicate ACK")
                    }
                }
            }
            else -> {
                log("RX ${packet.packetType}")
                if (packet.packetType !in doNotAckTypes && packet.packetType != PacketType.ACK) {
                    sendAck(packet.id)
                }
                _incomingPackets.tryEmit(packet)
            }
        }
    }

    private fun sendAck(ackId: String) {
        val ackPayload = com.remoteaudiosync.protocol.AckPayload(originalPacketId = ackId)
        val ackPacket = Packet(
            version = 1,
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            senderId = "android-client",
            receiverId = "desktop-server",
            packetType = PacketType.ACK,
            payload = ackPayload
        )
        sendInternal(ackPacket)
    }

    open fun send(packet: Packet) {
        if (packet.packetType in ackRequiredTypes) {
            coroutineScope.launch { sendWithAck(packet) }
        } else {
            sendInternal(packet)
        }
    }

    open suspend fun sendWithAck(packet: Packet): Boolean {
        var retries = 0
        while (retries <= 2) {
            val job = Job()
            pendingAcks[packet.id] = job
            sendInternal(packet)
            _connectionState.value = ConnectionState.WaitingForAck

            val ackReceived = withTimeoutOrNull(1500L) {
                job.join()
                !job.isActive
            } ?: false

            pendingAcks.remove(packet.id)

            if (ackReceived) {
                return true
            }

            retries++
        }

        log("Packet dropped after retries")
        _connectionState.value = ConnectionState.Failed("Retry limit exhausted")
        return false
    }

    private fun sendInternal(packet: Packet) {
        if (!_isAuthenticated.value && packet.packetType !in setOf(
                PacketType.PAIR_REQUEST, PacketType.PAIR_RESPONSE, PacketType.AUTH_REQUEST, PacketType.AUTH_SUCCESS, PacketType.ERROR, PacketType.ACK, PacketType.HEARTBEAT
            )) {
            log("Dropped ${packet.packetType} because not authenticated")
            return
        }
        var finalPacket = packet
        if (cryptoManager.sessionKey != null && packet.packetType !in setOf(
                PacketType.PAIR_REQUEST, PacketType.PAIR_RESPONSE, PacketType.AUTH_REQUEST, PacketType.AUTH_SUCCESS
            )) {
            val encryptResult = PacketCodec.encryptPayload(packet, cryptoManager)
            if (encryptResult is ProtocolResult.Success) {
                finalPacket = encryptResult.data
            }
        }

        val serialized = PacketCodec.serialize(finalPacket)
        if (serialized is ProtocolResult.Success) {
            webSocketClient.sendMessage(serialized.data)
            log("TX ${packet.packetType}")
        } else {
            log("Serialization failed")
        }
    }

    private fun startHeartbeat() {
        lastHeartbeatReceived = timeProvider()

        heartbeatJob?.cancel()
        heartbeatJob = coroutineScope.launch {
            while (isActive) {
                delay(5000L)
                val heartbeatPacket = Packet(
                    version = 1,
                    id = UUID.randomUUID().toString(),
                    timestamp = timeProvider(),
                    senderId = "android-client",
                    receiverId = "desktop-server",
                    packetType = PacketType.HEARTBEAT,
                    payload = com.remoteaudiosync.protocol.HeartbeatPayload(uptimeMillis = timeProvider())
                )
                sendInternal(heartbeatPacket)
            }
        }

        heartbeatMonitorJob?.cancel()
        heartbeatMonitorJob = coroutineScope.launch {
            while (isActive) {
                delay(1000L)
                if (timeProvider() - lastHeartbeatReceived > 10000L) {
                    log("Heartbeat timeout")
                    _connectionState.value = ConnectionState.Lost
                    reconnect()
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatMonitorJob?.cancel()
    }

    private fun reconnect() {
        setAuthenticated(false)
        coroutineScope.launch {
            log("Reconnect started")
            _connectionState.value = ConnectionState.Reconnecting
            webSocketClient.disconnect()
            delay(3000L)
            currentIp?.let { ip ->
                currentPort?.let { port ->
                    webSocketClient.connect(ip, port)
                }
            }
        }
    }

    private fun log(message: String) {
        _logs.tryEmit(message)
    }
}
