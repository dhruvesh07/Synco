package com.remoteaudiosync.desktop

import com.remoteaudiosync.network.ConnectionState
import com.remoteaudiosync.network.ReliableChannel
import com.remoteaudiosync.protocol.ArtworkRequestPayload
import com.remoteaudiosync.protocol.ArtworkResponsePayload
import com.remoteaudiosync.protocol.MediaCommandPayload
import com.remoteaudiosync.protocol.MediaStatePayload
import com.remoteaudiosync.protocol.Packet
import com.remoteaudiosync.protocol.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DesktopMediaManager(
    private val reliableChannel: ReliableChannel,
    private val observer: DesktopMediaSessionObserver,
    private val executor: DesktopMediaCommandExecutor,
    private val publisher: DesktopMediaStatePublisher,
    private val coroutineScope: CoroutineScope
) {
    private val _isAudioOwner = MutableStateFlow(false)
    val isAudioOwner: StateFlow<Boolean> = _isAudioOwner.asStateFlow()

    private val _mediaState = MutableStateFlow<MediaStatePayload?>(null)
    val mediaState: StateFlow<MediaStatePayload?> = _mediaState.asStateFlow()

    // Artwork downloaded from the phone (keyed by artwork id / sha256). Exposed so the web
    // dashboard can render the thumbnail of the track playing on the phone.
    private val artworkCache = ConcurrentHashMap<String, ByteArray>()
    private val _currentArtworkId = MutableStateFlow<String?>(null)
    val currentArtworkId: StateFlow<String?> = _currentArtworkId.asStateFlow()
    private val _currentArtwork = MutableStateFlow<ByteArray?>(null)
    val currentArtwork: StateFlow<ByteArray?> = _currentArtwork.asStateFlow()

    fun getArtwork(artworkId: String): ByteArray? = artworkCache[artworkId]

    private var observerJob: Job? = null
    private var packetCollectorJob: Job? = null
    private var connectionCollectorJob: Job? = null
    
    init {
        startListeningToChannel()
        startConnectionMonitoring()
    }

    fun setRole(isAudioOwner: Boolean) {
        this._isAudioOwner.value = isAudioOwner
        if (isAudioOwner) {
            startObservingLocalMedia()
            _currentArtwork.value = null
            _currentArtworkId.value = null
        } else {
            stopObservingLocalMedia()
            _mediaState.value = null
        }
    }

    private fun requestArtwork(artworkId: String) {
        if (artworkCache.containsKey(artworkId)) {
            if (_mediaState.value?.artworkId == artworkId) {
                _currentArtwork.value = artworkCache[artworkId]
            }
            return
        }
        if (_currentArtworkId.value == artworkId) return
        _currentArtworkId.value = artworkId
        _currentArtwork.value = null
        val packet = Packet(
            version = 1,
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            senderId = "desktop-server",
            receiverId = "android-client",
            packetType = PacketType.ARTWORK_REQUEST,
            payload = ArtworkRequestPayload(mediaId = artworkId)
        )
        coroutineScope.launch {
            reliableChannel.sendWithAck(packet)
        }
    }

    private fun startObservingLocalMedia() {
        stopObservingLocalMedia()
        observer.startObserving { state ->
            _mediaState.value = state
            publisher.publishState(state)
        }
        
        observerJob = coroutineScope.launch {
            while (true) {
                if (_isAudioOwner.value) {
                    val currentState = observer.getCurrentState()
                    _mediaState.value = currentState
                    publisher.publishState(currentState)
                }
                delay(2000)
            }
        }
    }

    private fun stopObservingLocalMedia() {
        observer.stopObserving()
        observerJob?.cancel()
        observerJob = null
    }

    private fun startListeningToChannel() {
        packetCollectorJob?.cancel()
        packetCollectorJob = coroutineScope.launch {
            reliableChannel.incomingPackets.collect { packet ->
                when (packet.packetType) {
                    PacketType.MEDIA_COMMAND -> {
                        if (_isAudioOwner.value) {
                            val payload = packet.payload as? MediaCommandPayload
                            if (payload != null) {
                                executeLocalCommand(payload)
                            }
                        }
                    }
                    PacketType.MEDIA_STATE -> {
                        if (!_isAudioOwner.value) {
                            val payload = packet.payload as? MediaStatePayload
                            if (payload != null) {
                                if (payload.title == "NO_ACTIVE_MEDIA_SESSION") {
                                    _mediaState.value = null
                                    _currentArtwork.value = null
                                    _currentArtworkId.value = null
                                } else {
                                    _mediaState.value = payload
                                    if (payload.artworkAvailable && !payload.artworkId.isNullOrBlank()) {
                                        requestArtwork(payload.artworkId)
                                    } else {
                                        _currentArtwork.value = null
                                        _currentArtworkId.value = null
                                    }
                                }
                            }
                        }
                    }
                    PacketType.ARTWORK_RESPONSE -> {
                        if (!_isAudioOwner.value) {
                            val payload = packet.payload as? ArtworkResponsePayload
                            if (payload != null && payload.artworkBase64.isNotBlank()) {
                                try {
                                    val bytes = Base64.getDecoder().decode(payload.artworkBase64)
                                    artworkCache[payload.mediaId] = bytes
                                    if (_currentArtworkId.value == payload.mediaId || _mediaState.value?.artworkId == payload.mediaId) {
                                        _currentArtwork.value = bytes
                                    }
                                } catch (e: Exception) {
                                    // Ignore malformed artwork
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun startConnectionMonitoring() {
        connectionCollectorJob?.cancel()
        connectionCollectorJob = coroutineScope.launch {
            reliableChannel.connectionState.collect { state ->
                if (state is ConnectionState.Connected) {
                    if (_isAudioOwner.value) {
                        publisher.publishState(_mediaState.value)
                        startObservingLocalMedia()
                    }
                }
            }
        }
    }

    fun sendCommand(command: String, seekPosition: Long? = null, volume: Int? = null) {
        if (_isAudioOwner.value) {
            executeLocalCommand(MediaCommandPayload(command, seekPosition, volume))
        } else {
            val packet = Packet(
                version = 1,
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                senderId = "desktop-server",
                receiverId = "android-client",
                packetType = PacketType.MEDIA_COMMAND,
                payload = MediaCommandPayload(command, seekPosition, volume)
            )
            coroutineScope.launch {
                reliableChannel.sendWithAck(packet)
            }
        }
    }

    private fun executeLocalCommand(payload: MediaCommandPayload) {
        executor.executeCommand(payload)
        if (_isAudioOwner.value) {
            val updatedState = observer.getCurrentState()
            _mediaState.value = updatedState
            publisher.publishState(updatedState)
        }
    }
    
    fun cleanup() {
        stopObservingLocalMedia()
        packetCollectorJob?.cancel()
        connectionCollectorJob?.cancel()
    }
}
