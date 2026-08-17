package com.remoteaudiosync.network

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class WebSocketClient {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    private var webSocket: WebSocket? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val logs = _logs.asSharedFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val messages = _messages.asSharedFlow()

    fun connect(ip: String, port: Int) {
        // A stale Connecting/Connected socket (e.g. after a process restart or a rejected
        // handshake) must not silently swallow a new connect() request. Tear it down first.
        if (_connectionState.value is ConnectionState.Connecting || _connectionState.value is ConnectionState.Connected) {
            webSocket?.cancel()
            webSocket = null
        }

        _connectionState.value = ConnectionState.Connecting
        val cleanIp = ip.trim()
        val scheme = if (port == 443 || cleanIp.startsWith("wss://")) "wss" else "ws"
        val url = if (cleanIp.startsWith("ws://") || cleanIp.startsWith("wss://") ||
            cleanIp.startsWith("http://") || cleanIp.startsWith("https://")) {
            cleanIp
                .replace("http://", "$scheme://")
                .replace("https://", "$scheme://")
                .replace("ws://", "$scheme://")
                .replace("wss://", "$scheme://")
        } else {
            "$scheme://$cleanIp:$port"
        }

                addLog("Connecting to $url...")
        System.out.println("[WS] Connecting to $url")

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.Connected
                addLog("Connected successfully")
                System.out.println("[WS] onOpen")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 65536) {
                    addLog("Rejected oversized message")
                    System.out.println("[WS] Rejected oversized message")
                    return
                }
                System.out.println("[WS] onMessage len=${text.length}")
                _messages.tryEmit(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                addLog("Closing: $code reason=$reason")
                System.out.println("[WS] onClosing code=$code reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.Disconnected
                addLog("Disconnected: $code reason=$reason")
                System.out.println("[WS] onClosed code=$code reason=$reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (this@WebSocketClient.webSocket != webSocket) return
                val errorMsg = t.message ?: "Unknown error"
                _connectionState.value = ConnectionState.Failed(errorMsg)
                addLog("Connection failed: $errorMsg")
                System.err.println("[WS] onFailure: $errorMsg")
                t.printStackTrace(System.err)
            }
        })
    }

    fun disconnect() {
        addLog("Disconnecting...")
        webSocket?.cancel()
        webSocket?.close(1000, "User initiated disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun clearError() {
        if (_connectionState.value is ConnectionState.Failed) {
            _connectionState.value = ConnectionState.Disconnected
            addLog("Error cleared")
        }
    }

    fun clearLogs() {
    }

    fun sendMessage(message: String) {
        val sender = customSender
        if (sender != null) {
            sender(message)
            return
        }
        if (_connectionState.value is ConnectionState.Connected) {
            webSocket?.send(message)
        } else {
            addLog("Cannot send message: Not connected")
        }
    }

    internal var customSender: ((String) -> Unit)? = null

    internal fun setServerConnected(connected: Boolean) {
        _connectionState.value = if (connected) ConnectionState.Connected else ConnectionState.Disconnected
    }

    internal fun feedIncomingMessage(message: String) {
        _messages.tryEmit(message)
    }

    private fun addLog(message: String) {
        _logs.tryEmit(message)
    }
}
