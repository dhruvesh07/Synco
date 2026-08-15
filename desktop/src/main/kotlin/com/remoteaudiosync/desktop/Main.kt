package com.remoteaudiosync.desktop

import com.remoteaudiosync.crypto.CryptoManager
import com.remoteaudiosync.manager.AudioRole
import com.remoteaudiosync.manager.DefaultAudioOwnerStateManager
import com.remoteaudiosync.manager.DefaultSourceSwitchManager
import com.remoteaudiosync.desktop.ui.DesktopToastNotifier
import com.remoteaudiosync.desktop.ui.DesktopTray
import com.remoteaudiosync.network.ConnectionState
import com.remoteaudiosync.network.ReliableChannel
import com.remoteaudiosync.network.WebSocketClient
import com.remoteaudiosync.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.io.File
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private fun encryptData(data: ByteArray, key: SecretKey): Pair<ByteArray, ByteArray> {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val ciphertext = cipher.doFinal(data)
    return Pair(ciphertext, cipher.iv)
}

private fun decryptData(ciphertext: ByteArray, iv: ByteArray, key: SecretKey): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val spec = GCMParameterSpec(128, iv)
    cipher.init(Cipher.DECRYPT_MODE, key, spec)
    return cipher.doFinal(ciphertext)
}

private fun loadOrCreateEncryptionKey(): SecretKey {
    val keyFile = File("desktop_identity.key")
    if (keyFile.exists()) {
        val encoded = Base64.getDecoder().decode(keyFile.readText().trim())
        return SecretKeySpec(encoded, "AES")
    }
    val keyGen = KeyGenerator.getInstance("AES")
    keyGen.init(256)
    val key = keyGen.generateKey()
    keyFile.writeText(Base64.getEncoder().encodeToString(key.encoded))
    keyFile.setReadable(true, true)
    keyFile.setWritable(true, true)
    return key
}

fun getOrGenerateDesktopIdentity(): Pair<ByteArray, ByteArray> {
    val file = File("desktop_identity.keys.enc")
    val encKey = loadOrCreateEncryptionKey()

    if (file.exists()) {
        try {
            val lines = file.readLines()
            if (lines.size >= 2) {
                val pub = Base64.getDecoder().decode(lines[0].trim())
                val privEncoded = Base64.getDecoder().decode(lines[1].trim())
                val iv = Base64.getDecoder().decode(lines[2].trim())
                val priv = decryptData(privEncoded, iv, encKey)
                return Pair(pub, priv)
            }
        } catch (e: Exception) {
            println("[ERROR] Failed to read desktop identity keys: ${e.message}")
        }
    }

    val generator = Ed25519KeyPairGenerator()
    generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
    val keyPair = generator.generateKeyPair()
    val pubBytes = (keyPair.public as Ed25519PublicKeyParameters).encoded
    val privBytes = (keyPair.private as Ed25519PrivateKeyParameters).encoded

    try {
        val (privEncrypted, iv) = encryptData(privBytes, encKey)
        file.writeText(
            "${Base64.getEncoder().encodeToString(pubBytes)}\n" +
            "${Base64.getEncoder().encodeToString(privEncrypted)}\n" +
            "${Base64.getEncoder().encodeToString(iv)}"
        )
        file.setReadable(true, true)
        file.setWritable(true, true)
        println("[INFO] Generated new encrypted persistent identity keys")
    } catch (e: Exception) {
        println("[ERROR] Failed to save desktop identity keys: ${e.message}")
    }

    return Pair(pubBytes, privBytes)
}

class DesktopAppServer(private val port: Int) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var serverCrypto: CryptoManager
    private lateinit var serverIdentityPub: ByteArray

    private val webSocketClient = WebSocketClient()
    private val stateManager = DefaultAudioOwnerStateManager("desktop-server", AudioRole.ACTIVE_AUDIO_OWNER)

    private val channelCrypto = CryptoManager()
    private val reliableChannel = ReliableChannel(webSocketClient, channelCrypto, scope)

    private val mediaObserver = DefaultDesktopMediaSessionObserver()
    private val mediaExecutor = DefaultDesktopMediaCommandExecutor()
    private val mediaPublisher = DefaultDesktopMediaStatePublisher(reliableChannel)
    private lateinit var mediaManager: DesktopMediaManager
    private lateinit var callManager: DesktopCallManager
    private lateinit var notificationManager: DesktopNotificationManager
    private lateinit var switchManager: DefaultSourceSwitchManager

    private var clientIdentityPub: ByteArray? = null
    private var activeWebSocket: WebSocket? = null
    private var isPairedAndAuthenticated = false
    private val pinCode = generatePin()
    private val clientMessageCounts = java.util.concurrent.ConcurrentHashMap<WebSocket, MutableList<Long>>()

    private lateinit var server: WebSocketServer
    private var webServer: com.remoteaudiosync.desktop.web.DesktopWebServer? = null

    fun getPinCode(): String = pinCode

    private fun generatePin(): String {
        val random = SecureRandom()
        val pin = (100000 + random.nextInt(900000)).toString()
        return pin
    }

    fun start() {
        val (pub, priv) = getOrGenerateDesktopIdentity()
        serverIdentityPub = pub

        serverCrypto = CryptoManager().apply {
            initIdentity(pub, priv)
        }
        channelCrypto.initIdentity(pub, priv)

        mediaManager = DesktopMediaManager(
            reliableChannel,
            mediaObserver,
            mediaExecutor,
            mediaPublisher,
            scope
        )
        mediaManager.setRole(isAudioOwner = true)

        callManager = DesktopCallManager(reliableChannel, scope)
        notificationManager = DesktopNotificationManager(reliableChannel, scope)

        // Wire real phone notifications & calls to native desktop alerts (Windows toasts).
        notificationManager.onNativeToast = { app, title, text, _ ->
            val heading = app.ifBlank { "Synco" }
            val body = buildString {
                if (title.isNotBlank()) {
                    append(title)
                    if (text.isNotBlank()) append("\n")
                }
                if (text.isNotBlank()) append(text)
            }.trim()
            DesktopToastNotifier.notify(heading, body.ifBlank { title })
        }
        callManager.onIncomingCall = { callerId ->
            val id = callerId ?: "Unknown"
            DesktopToastNotifier.notify("Phone Link · Call", "Calling from $id")
        }

        switchManager = DefaultSourceSwitchManager(
            reliableChannel,
            stateManager,
            scope
        ) { newRole ->
            val isOwner = newRole == AudioRole.ACTIVE_AUDIO_OWNER
            mediaManager.setRole(isOwner)
            println("[ROLE] Desktop transitioned to role: $newRole")
        }

        webSocketClient.setServerConnected(true)

        server = object : WebSocketServer(InetSocketAddress(port)) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                val origin = handshake.getFieldValue("Origin")
                if (!origin.isNullOrBlank()) {
                    val allowedPrefixes = listOf("http://localhost", "http://127.0.0.1", "file://", "http://[::1]", "http://10.", "http://172.", "http://192.168.")
                    if (allowedPrefixes.none { origin.startsWith(it) }) {
                        println("[SERVER] Rejecting connection from untrusted origin: $origin")
                        conn.close(1008, "Untrusted origin")
                        return
                    }
                }
                if (activeWebSocket != null && activeWebSocket != conn) {
                    println("[SERVER] Rejecting secondary connection from ${conn.remoteSocketAddress}")
                    conn.close(1013, "Server busy with active client")
                    return
                }
                activeWebSocket = conn
                isPairedAndAuthenticated = false
                webSocketClient.customSender = { conn.send(it) }
                webSocketClient.setServerConnected(true)
                println("\n[SERVER] Android client connected from ${conn.remoteSocketAddress}")
                println("[SERVER] Handshaking... Waiting for PAIR_REQUEST (PIN: $pinCode)")
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                if (activeWebSocket == conn) {
                    activeWebSocket = null
                    isPairedAndAuthenticated = false
                    reliableChannel.disconnect()
                    println("\n[SERVER] Android client disconnected — code=$code reason='$reason' remote=$remote")
                }
            }

            override fun onMessage(conn: WebSocket, message: String) {
                if (activeWebSocket != conn) return

                if (message.length > 65536) {
                    println("[SERVER] Rejected oversized message")
                    conn.close(1009, "Message too large")
                    return
                }

                val now = System.currentTimeMillis()
                val list = clientMessageCounts.computeIfAbsent(conn) { java.util.ArrayList() }
                synchronized(list) {
                    list.removeIf { now - it > 60000L }
                    if (list.size >= 300) {
                        println("[SERVER] Rate limit exceeded")
                        conn.close(1008, "Rate limit exceeded")
                        return
                    }
                    list.add(now)
                }

                if (!isPairedAndAuthenticated) {
                    handleHandshakeMessage(conn, message)
                } else {
                    webSocketClient.feedIncomingMessage(message)
                }
            }

            override fun onError(conn: WebSocket?, ex: java.lang.Exception) {
                println("[SERVER] Error: ${ex.message}")
            }

            override fun onStart() {
                println("[SERVER] WebSocket Server successfully launched on port $port")
            }
        }

        server.start()

        scope.launch {
            reliableChannel.logs.collect { log ->
                if (!log.contains("TX HEARTBEAT") && !log.contains("RX HEARTBEAT") && !log.contains("PING") && !log.contains("PONG")) {
                    println("[PROTO] $log")
                }
            }
        }

        scope.launch {
            reliableChannel.incomingPackets.collect { packet ->
                when (packet.packetType) {
                    com.remoteaudiosync.protocol.PacketType.ROLE_CHANGE_REQUEST -> {
                        val payload = packet.payload as? com.remoteaudiosync.protocol.RoleChangeRequestPayload
                        if (payload != null) {
                            switchManager.handleIncomingRequest(payload)
                        }
                    }
                    com.remoteaudiosync.protocol.PacketType.ROLE_STATE -> {
                        val payload = packet.payload as? com.remoteaudiosync.protocol.RoleStatePayload
                        if (payload != null) {
                            switchManager.handleIncomingState(payload)
                        }
                    }
                    else -> {}
                }
            }
        }

        scope.launch {
            mediaManager.mediaState.collectLatest { state ->
                if (state != null && state.title != "NO_ACTIVE_MEDIA_SESSION") {
                    println("[MEDIA] Current track: \"${state.title}\" by ${state.artist} [Playing: ${state.isPlaying}]")
                }
            }
        }

        webServer = com.remoteaudiosync.desktop.web.DesktopWebServer(8080, this)
        webServer?.start()

        installTray()
    }

    private fun installTray() {
        DesktopTray.onOpenDashboardRequested = { "http://localhost:8080?token=${webServer?.getAuthToken()}" }
        DesktopTray.onPinRequested = {
            DesktopToastNotifier.notify("Synco Pairing PIN", "PIN: $pinCode")
            println("[TRAY] Pairing PIN requested: $pinCode")
        }
        DesktopTray.install {
            println("[INFO] Quit requested from tray. Shutting down...")
            shutdown()
        }
        // Keep running in the background; register to start automatically on login.
        if (DesktopTray.isSupported()) {
            DesktopTray.enableAutoStart()
            println("[TRAY] Autostart enabled (background sync will persist across logins).")
        }
    }

    private fun shutdown() {
        stop()
        kotlin.system.exitProcess(0)
    }

    private fun handleHandshakeMessage(conn: WebSocket, text: String) {
        val deserializeResult = PacketCodec.deserialize(text)
        if (deserializeResult !is ProtocolResult.Success) {
            println("[HANDSHAKE] Malformed handshake package received")
            return
        }

        val validateResult = PacketValidator.validate(deserializeResult.data)
        if (validateResult is ProtocolResult.Failure) {
            println("[HANDSHAKE] Validation failed: ${validateResult.error.message}")
            return
        }
        val packet = (validateResult as ProtocolResult.Success).data

        when (packet.packetType) {
            PacketType.PAIR_REQUEST -> {
                val payload = packet.payload as? PairRequestPayload
                if (payload == null) {
                    sendHandshakeError(conn, "INVALID_PAYLOAD", "Payload must be PairRequestPayload")
                    return
                }
                println("[HANDSHAKE] Received PAIR_REQUEST from device: ${payload.deviceName}")
                if (payload.pin == pinCode) {
                    clientIdentityPub = Base64.getDecoder().decode(payload.publicKey)
                    println("[HANDSHAKE] PIN matched successfully! Sending PAIR_RESPONSE ACCEPTED")

                    val responsePayload = PairResponsePayload(
                        status = "ACCEPTED",
                        publicKey = Base64.getEncoder().encodeToString(serverIdentityPub)
                    )
                    val responsePacket = Packet(
                        version = 1,
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        senderId = "desktop-server",
                        receiverId = packet.senderId,
                        packetType = PacketType.PAIR_RESPONSE,
                        payload = responsePayload
                    )
                    val serializeResult = PacketCodec.serialize(responsePacket)
                    if (serializeResult is ProtocolResult.Success) {
                        conn.send(serializeResult.data)
                    }

                    val ackPacket = Packet(
                        version = 1,
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        senderId = "desktop-server",
                        receiverId = "android-client",
                        packetType = PacketType.ACK,
                        payload = com.remoteaudiosync.protocol.AckPayload(packet.id)
                    )
                    val ackResult = PacketCodec.serialize(ackPacket)
                    if (ackResult is ProtocolResult.Success) {
                        conn.send(ackResult.data)
                    }
                } else {
                    println("[HANDSHAKE] Wrong PIN submitted: ${payload.pin}. Rejecting.")
                    val responsePayload = PairResponsePayload(
                        status = "REJECTED",
                        publicKey = ""
                    )
                    val responsePacket = Packet(
                        version = 1,
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        senderId = "desktop-server",
                        receiverId = packet.senderId,
                        packetType = PacketType.PAIR_RESPONSE,
                        payload = responsePayload
                    )
                    val serializeResult = PacketCodec.serialize(responsePacket)
                    if (serializeResult is ProtocolResult.Success) {
                        conn.send(serializeResult.data)
                    }

                    val ackPacket = Packet(
                        version = 1,
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        senderId = "desktop-server",
                        receiverId = "android-client",
                        packetType = PacketType.ACK,
                        payload = com.remoteaudiosync.protocol.AckPayload(packet.id)
                    )
                    val ackResult = PacketCodec.serialize(ackPacket)
                    if (ackResult is ProtocolResult.Success) {
                        conn.send(ackResult.data)
                    }
                }
            }
            PacketType.AUTH_REQUEST -> {
                val payload = packet.payload as? AuthRequestPayload
                val clientPub = clientIdentityPub
                if (payload == null || clientPub == null) {
                    sendHandshakeError(conn, "AUTH_FAILED", "Identity not paired or invalid payload")
                    return
                }

                println("[HANDSHAKE] Received AUTH_REQUEST. Verifying client signature...")
                val ephPubBytes = Base64.getDecoder().decode(payload.ephemeralPublicKey)
                val sigBytes = Base64.getDecoder().decode(payload.signature)

                val signatureValid = serverCrypto.verifySignature(clientPub, ephPubBytes, sigBytes)
                if (!signatureValid) {
                    println("[HANDSHAKE] Verification failed: Client signature is invalid!")
                    sendHandshakeError(conn, "INVALID_SIGNATURE", "Client signature mismatch")
                    return
                }

                println("[HANDSHAKE] Signature verified! Formulating ephemeral response...")
                serverCrypto.generateEphemeralKeyPair()
                val serverEphPub = serverCrypto.ephemeralPublicKeyBytes!!
                val serverSig = serverCrypto.sign(serverEphPub)

                val responsePayload = AuthSuccessPayload(
                    ephemeralPublicKey = Base64.getEncoder().encodeToString(serverEphPub),
                    signature = Base64.getEncoder().encodeToString(serverSig),
                    sessionId = UUID.randomUUID().toString()
                )
                val responsePacket = Packet(
                    version = 1,
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    senderId = "desktop-server",
                    receiverId = packet.senderId,
                    packetType = PacketType.AUTH_SUCCESS,
                    payload = responsePayload
                )

                val serializeResult = PacketCodec.serialize(responsePacket)
                if (serializeResult is ProtocolResult.Success) {
                    conn.send(serializeResult.data)
                }

                val ackPacket = Packet(
                    version = 1,
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    senderId = "desktop-server",
                    receiverId = "android-client",
                    packetType = PacketType.ACK,
                    payload = com.remoteaudiosync.protocol.AckPayload(packet.id)
                )
                val ackResult = PacketCodec.serialize(ackPacket)
                if (ackResult is ProtocolResult.Success) {
                    conn.send(ackResult.data)
                }

                serverCrypto.deriveSessionKey(ephPubBytes)
                if (serverCrypto.sessionKey != null) {
                    channelCrypto.clearSessionKey()
                    channelCrypto.sessionKey = serverCrypto.sessionKey
                    println("[DEBUG] Derived session key propagated to channel.")
                } else {
                    println("[ERROR] sessionKey is NULL after derivation!")
                    return
                }

                isPairedAndAuthenticated = true
                reliableChannel.setAuthenticated(true)

                println("[HANDSHAKE] Secure session fully established with Android client!")

                val desktopModel = System.getProperty("os.name") ?: "Desktop Server"
                val desktopOs = System.getProperty("os.version") ?: "1.0"
                val deviceInfoPayload = DeviceInfoPayload(
                    model = desktopModel,
                    osVersion = desktopOs,
                    appVersion = "1.0.0"
                )
                val infoPacket = Packet(
                    version = 1,
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    senderId = "desktop-server",
                    receiverId = "android-client",
                    packetType = PacketType.DEVICE_INFO,
                    payload = deviceInfoPayload
                )
                scope.launch {
                    delay(500)
                    reliableChannel.send(infoPacket)
                    println("[HANDSHAKE] Sent Desktop DeviceInfo to Android client: $desktopModel ($desktopOs)")
                }
            }
            else -> {
                println("[HANDSHAKE] Received unexpected packet type before auth completed: ${packet.packetType}")
            }
        }
    }

    private fun sendHandshakeError(conn: WebSocket, code: String, msg: String) {
        val errorPacket = Packet(
            version = 1,
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            senderId = "desktop-server",
            receiverId = "android-client",
            packetType = PacketType.ERROR,
            payload = ErrorPayload(code, msg)
        )
        val serializeResult = PacketCodec.serialize(errorPacket)
        if (serializeResult is ProtocolResult.Success) {
            conn.send(serializeResult.data)
        }
    }

    fun stop() {
        scope.cancel()
        server.stop()
        webServer?.stop()
        mediaManager.cleanup()
        callManager.stop()
        notificationManager.stop()
        DesktopTray.remove()
    }

    fun isConnected(): Boolean = isPairedAndAuthenticated
    fun isAudioOwner(): Boolean = stateManager.currentRole.value == AudioRole.ACTIVE_AUDIO_OWNER
    fun getMediaState(): MediaStatePayload? = mediaManager.mediaState.value
    fun getNotifications(): List<NotificationStatePayload> = notificationManager.notifications.value
    fun getCallState(): String = callManager.callState.value
    fun getCallerId(): String? = callManager.callerId.value
    fun getCurrentArtworkId(): String? = mediaManager.currentArtworkId.value
    fun getArtwork(id: String): ByteArray? = mediaManager.getArtwork(id)

    fun triggerPlay() {
        mediaManager.sendCommand("PLAY")
    }

    fun triggerPause() {
        mediaManager.sendCommand("PAUSE")
    }

    fun triggerNext() {
        mediaManager.sendCommand("NEXT")
    }

    fun triggerPrevious() {
        mediaManager.sendCommand("PREVIOUS")
    }

    fun triggerVolume(vol: Int) {
        mediaManager.sendCommand("SET_VOLUME", volume = vol)
    }

    fun requestRoleSwitch() {
        val current = stateManager.currentRole.value
        val target = if (current == AudioRole.ACTIVE_AUDIO_OWNER) AudioRole.REMOTE_CONTROLLER else AudioRole.ACTIVE_AUDIO_OWNER
        println("[ROLE] Requesting switch to target role: $target")
        scope.launch {
            val result = switchManager.initiateSwitch(target)
            println("[ROLE] Switch request result: $result")
        }
    }

    fun simulateCall(state: String, callerId: String) {
        if (!isPairedAndAuthenticated) {
            println("[SIM] Cannot send call state: No client connected")
            return
        }
        val callPacket = Packet(
            version = 1,
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            senderId = "desktop-server",
            receiverId = "android-client",
            packetType = PacketType.CALL_STATE,
            payload = CallStatePayload(state = state, callerId = callerId)
        )
        scope.launch {
            reliableChannel.send(callPacket)
            println("[SIM] Sent simulated call state '$state' for caller '$callerId' to Android client")
        }
    }

    fun simulateNotification(action: String, id: String, title: String, text: String) {
        if (!isPairedAndAuthenticated) {
            println("[SIM] Cannot send notification: No client connected")
            return
        }
        val notifPacket = Packet(
            version = 1,
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            senderId = "desktop-server",
            receiverId = "android-client",
            packetType = PacketType.NOTIFICATION_STATE,
            payload = NotificationStatePayload(
                id = id,
                packageName = "com.remoteaudiosync.desktop",
                appName = "Desktop App",
                title = title,
                text = text,
                isOngoing = false,
                action = action
            )
        )
        scope.launch {
            reliableChannel.send(notifPacket)
            println("[SIM] Sent simulated notification action '$action' to Android client")
        }
    }
}

fun main() {
    println("=================================================================")
    println("                     Synco - Desktop Server v1.0.0               ")
    println("=================================================================")
    println("[INFO] Booting up subproject environment...")

    val appServer = DesktopAppServer(8765)
    appServer.start()

    println("\n[CONSOLE] Interactive Shell ready. Type 'help' to see controls.")
    println("[CONSOLE] Listening on Port: 8765 | PIN Code: ${appServer.getPinCode()}\n")

    val reader = java.io.BufferedReader(java.io.InputStreamReader(System.`in`))
    while (true) {
        print("> ")
        val input = reader.readLine()?.trim() ?: break
        if (input.isEmpty()) continue

        val parts = input.split(" ")
        when (parts[0].lowercase()) {
            "help" -> {
                println("\nAvailable Commands:")
                println("  play / p            - Send PLAY command")
                println("  pause / s           - Send PAUSE command")
                println("  next / n            - Send NEXT track command")
                println("  prev / r            - Send PREVIOUS track command")
                println("  volume / v <0-100>  - Change media volume (e.g. 'v 75')")
                println("  role                - Switch Bluetooth / Audio ownership roles")
                println("  call <state> <id>   - Simulate incoming/active call to Android")
                println("                        states: incoming, answered, ended")
                println("                        example: 'call incoming +15550199'")
                println("  notif <act> <id> <title> <text>")
                println("                      - Simulate notification updates to Android")
                println("                        actions: ADDED, REMOVED, UPDATED")
                println("                        example: 'notif ADDED 101 Spotify \"Now Playing\"'")
                println("  exit / q            - Shut down server and exit")
                println("  help                - Display this menu\n")
                println("Background: Real phone notifications & calls surface as native Windows")
                println("  toasts. A tray icon is active (right-click for Open Dashboard /")
                println("  PIN / Autostart / Quit). Autostart on login is registered so the")
                println("  daemon keeps syncing in the background.\n")
            }
            "play", "p" -> appServer.triggerPlay()
            "pause", "s" -> appServer.triggerPause()
            "next", "n" -> appServer.triggerNext()
            "prev", "r" -> appServer.triggerPrevious()
            "volume", "v" -> {
                val vol = parts.getOrNull(1)?.toIntOrNull()
                if (vol != null && vol in 0..100) {
                    appServer.triggerVolume(vol)
                } else {
                    println("[CONSOLE] Usage: volume / v <0-100>")
                }
            }
            "role" -> appServer.requestRoleSwitch()
            "call" -> {
                val state = parts.getOrNull(1)
                val callerId = parts.getOrNull(2)
                if (state != null && callerId != null) {
                    appServer.simulateCall(state, callerId)
                } else {
                    println("[CONSOLE] Usage: call <state> <callerId>")
                }
            }
            "notif" -> {
                val action = parts.getOrNull(1)
                val id = parts.getOrNull(2)
                val title = parts.getOrNull(3)
                val text = parts.getOrNull(4)
                if (action != null && id != null && title != null && text != null) {
                    appServer.simulateNotification(action, id, title, text)
                } else {
                    println("[CONSOLE] Usage: notif <action> <id> <title> <text>")
                }
            }
            "exit", "q", "quit" -> {
                println("[INFO] Shutting down Server...")
                appServer.stop()
                DesktopTray.remove()
                println("[INFO] Server stopped. Goodbye!")
                break
            }
            else -> {
                println("[CONSOLE] Unknown command: '${parts[0]}'. Type 'help' for instructions.")
            }
        }
    }
}
