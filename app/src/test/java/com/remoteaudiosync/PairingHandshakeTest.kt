package com.remoteaudiosync

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.remoteaudiosync.crypto.CryptoManager
import com.remoteaudiosync.crypto.IdentityKeyStore
import com.remoteaudiosync.manager.PairingManager
import com.remoteaudiosync.manager.PairingResult
import com.remoteaudiosync.manager.TrustedDeviceManager
import com.remoteaudiosync.network.WebSocketClient
import com.remoteaudiosync.protocol.AuthSuccessPayload
import com.remoteaudiosync.protocol.Packet
import com.remoteaudiosync.protocol.PacketCodec
import com.remoteaudiosync.protocol.PacketType
import com.remoteaudiosync.protocol.PairResponsePayload
import com.remoteaudiosync.protocol.ProtocolResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import java.security.SecureRandom
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PairingHandshakeTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var webSocketClient: WebSocketClient
    private lateinit var pairingManager: PairingManager
    private lateinit var trustedDeviceManager: TrustedDeviceManager
    private lateinit var identityKeyStore: IdentityKeyStore
    private lateinit var cryptoManager: CryptoManager
    
    private lateinit var serverCrypto: CryptoManager
    private lateinit var serverIdentityPub: ByteArray

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val context = ApplicationProvider.getApplicationContext<Context>()

        val trustedDevices = mutableMapOf<String, String>()
        trustedDeviceManager = mock<TrustedDeviceManager> {
            on { saveTrustedDevice(any(), any()) }.thenAnswer { inv ->
                trustedDevices[inv.getArgument<String>(0)] = inv.getArgument<String>(1)
            }
            on { isTrusted(any()) }.thenAnswer { inv ->
                trustedDevices.containsKey(inv.getArgument<String>(0))
            }
            on { getTrustedDevicePublicKey(any()) }.thenAnswer { inv ->
                trustedDevices[inv.getArgument<String>(0)]
            }
            on { savePairPin(any()) }.thenAnswer { }
            on { getPairPin() }.thenReturn(null)
            on { hasStoredPin() }.thenReturn(false)
            on { clearPairPin() }.thenAnswer { }
        }
        
        val testPrefs = context.getSharedPreferences("test_identity_prefs", Context.MODE_PRIVATE)
        identityKeyStore = IdentityKeyStore(context, testPrefs)
        
        cryptoManager = CryptoManager()
        webSocketClient = WebSocketClient()
        val reliableChannel = com.remoteaudiosync.network.ReliableChannel(webSocketClient, cryptoManager, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        pairingManager = PairingManager(reliableChannel, trustedDeviceManager, identityKeyStore, cryptoManager)
        
        serverCrypto = CryptoManager()
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = generator.generateKeyPair()
        serverIdentityPub = (keyPair.public as Ed25519PublicKeyParameters).encoded
        serverCrypto.initIdentity(serverIdentityPub, (keyPair.private as Ed25519PrivateKeyParameters).encoded)
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
        webSocketClient.disconnect()
    }

    @Test
    fun `test successful pairing`() = runBlocking {
        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val packetResult = PacketCodec.deserialize(text) as? ProtocolResult.Success
                val packet = packetResult?.data ?: return
                
                if (packet.packetType == PacketType.PAIR_REQUEST) {
                    val responsePayload = PairResponsePayload(
                        status = "ACCEPTED",
                        publicKey = Base64.encodeToString(serverIdentityPub, Base64.NO_WRAP)
                    )
                    val responsePacket = Packet(
                        version = 1,
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        senderId = "desktop-server",
                        receiverId = "android-client",
                        packetType = PacketType.PAIR_RESPONSE,
                        payload = responsePayload
                    )
                    val json = (PacketCodec.serialize(responsePacket) as ProtocolResult.Success).data
                    webSocket.send(json)
                } else if (packet.packetType == PacketType.AUTH_REQUEST) {
                    serverCrypto.generateEphemeralKeyPair()
                    val serverEphPub = serverCrypto.ephemeralPublicKeyBytes!!
                    val serverSig = serverCrypto.sign(serverEphPub)
                    
                    val responsePayload = AuthSuccessPayload(
                        ephemeralPublicKey = Base64.encodeToString(serverEphPub, Base64.NO_WRAP),
                        signature = Base64.encodeToString(serverSig, Base64.NO_WRAP),
                        sessionId = "session-123"
                    )
                    val responsePacket = Packet(
                        version = 1,
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        senderId = "desktop-server",
                        receiverId = "android-client",
                        packetType = PacketType.AUTH_SUCCESS,
                        payload = responsePayload
                    )
                    val json = (PacketCodec.serialize(responsePacket) as ProtocolResult.Success).data
                    webSocket.send(json)
                }
            }
        }))

        webSocketClient.connect(mockWebServer.hostName, mockWebServer.port)
        delay(100)
        
        val result = pairingManager.initiatePairing("123456")
        assertTrue("Expected PairingResult.Success but got $result", result is PairingResult.Success)
        assertTrue(trustedDeviceManager.isTrusted("desktop-server"))
        assertEquals(Base64.encodeToString(serverIdentityPub, Base64.NO_WRAP), trustedDeviceManager.getTrustedDevicePublicKey("desktop-server"))
    }

    @Test
    fun `test wrong PIN`() = runBlocking {
        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val responsePayload = PairResponsePayload(status = "REJECTED", publicKey = "server-public-key")
                val responsePacket = Packet(
                    version = 1,
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    senderId = "desktop-server",
                    receiverId = "android-client",
                    packetType = PacketType.PAIR_RESPONSE,
                    payload = responsePayload
                )
                val json = (PacketCodec.serialize(responsePacket) as ProtocolResult.Success).data
                webSocket.send(json)
            }
        }))

        webSocketClient.connect(mockWebServer.hostName, mockWebServer.port)
        delay(100)
        
        val result = pairingManager.initiatePairing("000000")
        assertTrue("Expected PairingResult.Failed", result is PairingResult.Failed)
        val failed = result as PairingResult.Failed
        assertTrue(failed.reason.contains("REJECTED"))
    }

    @Test
    fun `test malformed response`() = runBlocking {
        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("{ invalid json }")
            }
        }))

        webSocketClient.connect(mockWebServer.hostName, mockWebServer.port)
        delay(100)
        
        val result = pairingManager.initiatePairing("123456")
        assertTrue("Expected PairingResult.Failed for malformed response", result is PairingResult.Failed)
    }

    @Test
    fun `test timeout`() = runBlocking {
        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Do nothing, just let it timeout
            }
        }))

        webSocketClient.connect(mockWebServer.hostName, mockWebServer.port)
        delay(100)
        
        val result = pairingManager.initiatePairing("123456")
        assertTrue("Expected PairingResult.Failed for timeout", result is PairingResult.Failed)
        val failed = result as PairingResult.Failed
        assertTrue(failed.reason.contains("Timeout"))
    }
}
