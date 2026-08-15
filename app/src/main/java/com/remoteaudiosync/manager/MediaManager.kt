package com.remoteaudiosync.manager

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import com.remoteaudiosync.network.ReliableChannel
import com.remoteaudiosync.protocol.MediaCommandPayload
import com.remoteaudiosync.protocol.MediaStatePayload
import com.remoteaudiosync.protocol.Packet
import com.remoteaudiosync.protocol.PacketType
import com.remoteaudiosync.service.MediaNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class MediaManager(
    private val context: Context,
    private val reliableChannel: ReliableChannel,
    private val coroutineScope: CoroutineScope,
    private val artworkCache: com.remoteaudiosync.artwork.ArtworkCache? = null
) {
    private val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _mediaState = MutableStateFlow<MediaStatePayload?>(null)
    val mediaState: StateFlow<MediaStatePayload?> = _mediaState.asStateFlow()

    private val _hasNotificationPermission = MutableStateFlow(false)
    val hasNotificationPermission: StateFlow<Boolean> = _hasNotificationPermission.asStateFlow()

    private var activeController: MediaController? = null
    private var isAudioOwner = false
    
    private var updateJob: Job? = null
    private var listenerJob: Job? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveSession(controllers)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateMediaState()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMediaState()
        }
    }

    init {
        checkPermission()
        startListeningToChannel()
    }

    fun setRole(isAudioOwner: Boolean) {
        this.isAudioOwner = isAudioOwner
        if (isAudioOwner) {
            checkPermission()
            if (_hasNotificationPermission.value) {
                // MediaSessionManager calls must run on a thread with a Looper (main thread).
                postOnMainThread {
                    try {
                        val component = MediaNotificationListenerService.getComponentName(context)
                        mediaSessionManager.addOnActiveSessionsChangedListener(sessionListener, component)
                        updateActiveSession(mediaSessionManager.getActiveSessions(component))
                        startPeriodicUpdate()
                    } catch (e: SecurityException) {
                        _hasNotificationPermission.value = false
                    } catch (e: RuntimeException) {
                        _hasNotificationPermission.value = false
                    }
                }
            }
        } else {
            postOnMainThread {
                try {
                    mediaSessionManager.removeOnActiveSessionsChangedListener(sessionListener)
                } catch (e: Exception) {}
                activeController?.unregisterCallback(controllerCallback)
                activeController = null
                stopPeriodicUpdate()
            }
        }
    }

    private fun postOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }

    fun checkPermission() {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
        val hasPermission = enabledListeners.contains(context.packageName)
        _hasNotificationPermission.value = hasPermission
        if (!hasPermission && isAudioOwner) {
            _mediaState.value = null
            // If permission is missing, show real missing-permission state.
            publishMediaState(MediaStatePayload("MISSING_PERMISSION", "", false, 0, 0, ""))
        }
    }

    private fun updateActiveSession(controllers: List<MediaController>?) {
        activeController?.unregisterCallback(controllerCallback)
        
        activeController = controllers?.firstOrNull()
        
        activeController?.registerCallback(controllerCallback)
        updateMediaState()
    }

    private fun updateMediaState() {
        if (!isAudioOwner) return

        if (!_hasNotificationPermission.value) {
            _mediaState.value = null
            publishMediaState(MediaStatePayload("MISSING_PERMISSION", "", false, 0, 0, ""))
            return
        }

        val controller = activeController
        if (controller == null) {
            _mediaState.value = null
            publishMediaState(null) // will convert to NO_ACTIVE_MEDIA_SESSION
            return
        }

        val metadata = controller.metadata
        val pbState = controller.playbackState
        
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown"
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        
        val isPlaying = pbState?.state == PlaybackState.STATE_PLAYING
        val position = pbState?.position ?: 0L

        var appName = controller.packageName
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(appName, 0)
            appName = pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {}

        val rawBytes = getArtworkBytes(metadata)
        val artworkBytes = if (rawBytes != null) com.remoteaudiosync.artwork.ArtworkCache.downscaleIfNeeded(rawBytes) else null
        val artworkId: String?
        val artworkAvailable: Boolean
        if (artworkBytes != null && artworkCache != null) {
            val hash = com.remoteaudiosync.artwork.ArtworkCache.generateSha256(artworkBytes)
            artworkId = hash
            artworkAvailable = true
            artworkCache.put(hash, artworkBytes)
        } else {
            artworkId = null
            artworkAvailable = false
        }

        val volume = try {
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            100
        }
        val isMuted = try {
            audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            false
        }

        val state = MediaStatePayload(
            title = title,
            artist = artist,
            isPlaying = isPlaying,
            position = position,
            duration = duration,
            appName = appName,
            volume = volume,
            isMuted = isMuted,
            artworkId = artworkId,
            artworkAvailable = artworkAvailable
        )
        _mediaState.value = state
        publishMediaState(state)
    }

    private fun getArtworkBytes(metadata: MediaMetadata?): ByteArray? {
        if (metadata == null) return null
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: return null
        return try {
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun startPeriodicUpdate() {
        updateJob?.cancel()
        updateJob = coroutineScope.launch {
            while (true) {
                if (activeController != null && activeController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    updateMediaState()
                }
                delay(1000)
            }
        }
    }

    private fun stopPeriodicUpdate() {
        updateJob?.cancel()
        updateJob = null
    }

    private var lastPublishedKey: String? = null

    private fun publishMediaState(state: MediaStatePayload?) {
        if (!reliableChannel.isAuthenticated.value) return
        val payload = state ?: MediaStatePayload("NO_ACTIVE_MEDIA_SESSION", "", false, 0, 0, "")
        val key = "${payload.title}|${payload.artist}|${payload.isPlaying}|${payload.position}|${payload.volume}"
        if (key == lastPublishedKey) return
        lastPublishedKey = key
        val packet = Packet(
            version = 1,
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            senderId = "android-client",
            receiverId = "desktop-server",
            packetType = PacketType.MEDIA_STATE,
            payload = payload
        )
        reliableChannel.send(packet)
    }

    private fun startListeningToChannel() {
        listenerJob = coroutineScope.launch {
            reliableChannel.incomingPackets.collect { packet ->
                when (packet.packetType) {
                    PacketType.MEDIA_COMMAND -> {
                        val payload = packet.payload as? MediaCommandPayload
                        if (payload != null) {
                            executeCommand(payload)
                        }
                    }
                    PacketType.MEDIA_STATE -> {
                        if (!isAudioOwner) {
                            val payload = packet.payload as? MediaStatePayload
                            if (payload != null) {
                                if (payload.title == "NO_ACTIVE_MEDIA_SESSION" || payload.title == "MISSING_PERMISSION") {
                                    _mediaState.value = null
                                } else {
                                    _mediaState.value = payload
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun sendCommand(command: String, seekPosition: Long? = null) {
        if (isAudioOwner) {
            executeCommand(MediaCommandPayload(command, seekPosition))
        } else {
            val packet = Packet(
                version = 1,
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                senderId = "android-client",
                receiverId = "desktop-server",
                packetType = PacketType.MEDIA_COMMAND,
                payload = MediaCommandPayload(command, seekPosition)
            )
            coroutineScope.launch {
                reliableChannel.sendWithAck(packet)
            }
        }
    }

    private fun executeCommand(payload: MediaCommandPayload) {
        val controller = activeController
        when (payload.command) {
            "PLAY" -> controller?.transportControls?.play()
            "PAUSE" -> controller?.transportControls?.pause()
            "NEXT" -> controller?.transportControls?.skipToNext()
            "PREVIOUS" -> controller?.transportControls?.skipToPrevious()
            "SEEK" -> payload.seekPosition?.let { controller?.transportControls?.seekTo(it) }
            "VOLUME_UP" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "VOLUME_DOWN" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "SET_VOLUME" -> {
                val vol = payload.volume
                if (vol != null) {
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val targetVol = (vol * maxVol / 100).coerceIn(0, maxVol)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
                }
            }
            "MUTE" -> {
                val isMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (isMuted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
        }
    }
}
