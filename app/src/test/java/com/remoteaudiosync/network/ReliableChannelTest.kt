package com.remoteaudiosync.network

import com.remoteaudiosync.crypto.CryptoManager
import com.remoteaudiosync.protocol.Packet
import com.remoteaudiosync.protocol.PacketCodec
import com.remoteaudiosync.protocol.PacketType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ReliableChannelTest {

    private lateinit var webSocketClient: WebSocketClient
    private lateinit var cryptoManager: CryptoManager

    @Before
    fun setup() {
        webSocketClient = WebSocketClient()
        cryptoManager = CryptoManager()
    }

    @Test
    fun `test ACK success`() = runTest {
        val reliableChannel = ReliableChannel(webSocketClient, cryptoManager, backgroundScope) { currentTime }
        val packet = Packet(1, UUID.randomUUID().toString(), System.currentTimeMillis(), "s", "r", PacketType.MEDIA_COMMAND)
        
        val sendDeferred = async {
            reliableChannel.sendWithAck(packet)
        }
        
        runCurrent()
        advanceTimeBy(100)
        
        val ackPayload = com.remoteaudiosync.protocol.AckPayload(originalPacketId = packet.id)
        val ackPacket = Packet(1, UUID.randomUUID().toString(), System.currentTimeMillis(), "r", "s", PacketType.ACK, ackPayload)
        
        val ackJson = PacketCodec.serialize(ackPacket)
        
        val field = webSocketClient.javaClass.getDeclaredField("_messages")
        field.isAccessible = true
        val flow = field.get(webSocketClient) as kotlinx.coroutines.flow.MutableSharedFlow<String>
        flow.emit((ackJson as com.remoteaudiosync.protocol.ProtocolResult.Success).data)
        
        assertTrue(sendDeferred.await())
    }

    @Test
    fun `test ACK timeout and retry`() = runTest {
        val reliableChannel = ReliableChannel(webSocketClient, cryptoManager, backgroundScope) { currentTime }
        val packet = Packet(1, UUID.randomUUID().toString(), System.currentTimeMillis(), "s", "r", PacketType.MEDIA_COMMAND)
        
        val sendDeferred = async {
            reliableChannel.sendWithAck(packet)
        }
        
        runCurrent()
        advanceTimeBy(850) // 1st timeout
        
        val ackPayload = com.remoteaudiosync.protocol.AckPayload(originalPacketId = packet.id)
        val ackPacket = Packet(1, UUID.randomUUID().toString(), System.currentTimeMillis(), "r", "s", PacketType.ACK, ackPayload)
        val ackJson = PacketCodec.serialize(ackPacket) as com.remoteaudiosync.protocol.ProtocolResult.Success
        
        val field = webSocketClient.javaClass.getDeclaredField("_messages")
        field.isAccessible = true
        val flow = field.get(webSocketClient) as kotlinx.coroutines.flow.MutableSharedFlow<String>
        flow.emit(ackJson.data)
        
        assertTrue(sendDeferred.await())
    }

    @Test
    fun `test Retry exhausted`() = runTest {
        val reliableChannel = ReliableChannel(webSocketClient, cryptoManager, backgroundScope) { currentTime }
        val packet = Packet(1, UUID.randomUUID().toString(), System.currentTimeMillis(), "s", "r", PacketType.MEDIA_COMMAND)
        
        val sendDeferred = async {
            reliableChannel.sendWithAck(packet)
        }
        
        runCurrent()
        advanceTimeBy(2500) // 3 timeouts of 800ms = 2400ms
        
        assertFalse(sendDeferred.await())
        assertTrue(reliableChannel.connectionState.value is ConnectionState.Failed)
    }

    @Test
    fun `test Heartbeat timeout`() = runTest {
        val reliableChannel = ReliableChannel(webSocketClient, cryptoManager, backgroundScope) { currentTime }
        reliableChannel.setAuthenticated(true)
        val field = webSocketClient.javaClass.getDeclaredField("_connectionState")
        field.isAccessible = true
        val stateFlow = field.get(webSocketClient) as kotlinx.coroutines.flow.MutableStateFlow<ConnectionState>
        stateFlow.value = ConnectionState.Connected
        
        runCurrent()
        advanceTimeBy(11500) // 11.5s timeout (check is every 1s, condition is > 10s)
        
        assertEquals(ConnectionState.Reconnecting, reliableChannel.connectionState.value)
    }

    @Test
    fun `test Reconnect`() = runTest {
        val reliableChannel = ReliableChannel(webSocketClient, cryptoManager, backgroundScope) { currentTime }
        reliableChannel.setAuthenticated(true)
        reliableChannel.connect("127.0.0.1", 8080)
        webSocketClient.disconnect()
        
        val field = webSocketClient.javaClass.getDeclaredField("_connectionState")
        field.isAccessible = true
        val stateFlow = field.get(webSocketClient) as kotlinx.coroutines.flow.MutableStateFlow<ConnectionState>
        stateFlow.value = ConnectionState.Connected
        
        runCurrent()
        advanceTimeBy(11500) // 11.5s timeout -> Lost
        
        assertEquals(ConnectionState.Reconnecting, reliableChannel.connectionState.value)
        advanceTimeBy(3500) // 3s reconnect delay
        
        // Reconnect should have been called
        assertEquals(ConnectionState.Connecting, webSocketClient.connectionState.value)
    }

    @Test
    fun `test Packet router and multiple subscribers`() = runTest {
        val reliableChannel = ReliableChannel(webSocketClient, cryptoManager, backgroundScope) { currentTime }
        val packet1 = Packet(1, UUID.randomUUID().toString(), System.currentTimeMillis(), "s", "r", PacketType.MEDIA_COMMAND, com.remoteaudiosync.protocol.MediaCommandPayload(command = "play"))
        val packet2 = Packet(1, UUID.randomUUID().toString(), System.currentTimeMillis(), "s", "r", PacketType.MEDIA_STATE, com.remoteaudiosync.protocol.MediaStatePayload(title = "t", artist = "a", isPlaying = false, position = 0, duration = 0))
        
        val sub1 = async { reliableChannel.incomingPackets.take(2).toList() }
        val sub2 = async { reliableChannel.incomingPackets.take(2).toList() }
        
        runCurrent() // Allow subscribers to start collecting
        
        val field = webSocketClient.javaClass.getDeclaredField("_messages")
        field.isAccessible = true
        val flow = field.get(webSocketClient) as kotlinx.coroutines.flow.MutableSharedFlow<String>
        
        val p1Json = (PacketCodec.serialize(packet1) as com.remoteaudiosync.protocol.ProtocolResult.Success).data
        val p2Json = (PacketCodec.serialize(packet2) as com.remoteaudiosync.protocol.ProtocolResult.Success).data
        
        flow.emit(p1Json)
        flow.emit(p2Json)
        
        val list1 = sub1.await()
        val list2 = sub2.await()
        
        assertEquals(2, list1.size)
        assertEquals(2, list2.size)
        assertEquals(PacketType.MEDIA_COMMAND, list1[0].packetType)
        assertEquals(PacketType.MEDIA_STATE, list1[1].packetType)
    }

    @Test
    fun `test Duplicate ACK`() = runTest {
        val reliableChannel = ReliableChannel(webSocketClient, cryptoManager, backgroundScope) { currentTime }
        val ackPayload = com.remoteaudiosync.protocol.AckPayload(originalPacketId = "someId")
        val ackPacket = Packet(1, UUID.randomUUID().toString(), System.currentTimeMillis(), "r", "s", PacketType.ACK, ackPayload)
        val ackJson = (PacketCodec.serialize(ackPacket) as com.remoteaudiosync.protocol.ProtocolResult.Success).data
        
        val field = webSocketClient.javaClass.getDeclaredField("_messages")
        field.isAccessible = true
        val flow = field.get(webSocketClient) as kotlinx.coroutines.flow.MutableSharedFlow<String>
        
        val logField = reliableChannel.javaClass.getDeclaredField("_logs")
        logField.isAccessible = true
        val logFlow = logField.get(reliableChannel) as kotlinx.coroutines.flow.MutableSharedFlow<String>
        
        val logs = mutableListOf<String>()
        backgroundScope.launch {
            logFlow.collect { logs.add(it) }
        }
        
        runCurrent()
        flow.emit(ackJson)
        runCurrent()
        flow.emit(ackJson)
        runCurrent()
        
        assertTrue("Expected 'Duplicate ACK' in logs, got: $logs", logs.contains("Duplicate ACK"))
    }

    @Test
    fun `test Unknown packet`() = runTest {
        val reliableChannel = ReliableChannel(webSocketClient, cryptoManager, backgroundScope) { currentTime }
        val field = webSocketClient.javaClass.getDeclaredField("_messages")
        field.isAccessible = true
        val flow = field.get(webSocketClient) as kotlinx.coroutines.flow.MutableSharedFlow<String>
        
        val logField = reliableChannel.javaClass.getDeclaredField("_logs")
        logField.isAccessible = true
        val logFlow = logField.get(reliableChannel) as kotlinx.coroutines.flow.MutableSharedFlow<String>
        
        val logs = mutableListOf<String>()
        backgroundScope.launch {
            logFlow.collect { logs.add(it) }
        }
        
        runCurrent()
        flow.emit("invalid json")
        runCurrent()
        
        assertTrue("Expected 'Malformed packet received' in logs, got: $logs", logs.contains("Malformed packet received"))
    }
}
