package com.remoteaudiosync.desktop.web

import com.remoteaudiosync.desktop.DesktopAppServer
import io.javalin.Javalin
import io.javalin.websocket.WsContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.security.SecureRandom
import java.util.Base64

class DesktopWebServer(
    private val port: Int,
    private val appServer: DesktopAppServer
) {
    private var app: Javalin? = null
    private val connectedClients = ConcurrentHashMap.newKeySet<WsContext>()
    private val scope = CoroutineScope(Dispatchers.Default)

    private val requestCounts = ConcurrentHashMap<String, MutableList<Long>>()
    private val wsMessageCounts = ConcurrentHashMap<String, MutableList<Long>>()

    private val authToken: String = generateAuthToken()
    private val validTokens = ConcurrentHashMap.newKeySet<String>().also { it.add(authToken) }

    fun getAuthToken(): String = authToken

    private fun generateAuthToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun isRateLimited(ip: String, maxRequests: Int, windowMs: Long, cache: ConcurrentHashMap<String, MutableList<Long>>): Boolean {
        val now = System.currentTimeMillis()
        val list = cache.computeIfAbsent(ip) { java.util.ArrayList() }
        synchronized(list) {
            list.removeIf { now - it > windowMs }
            if (list.size >= maxRequests) {
                return true
            }
            list.add(now)
        }
        return false
    }

    private fun isAuthorized(ctx: io.javalin.http.Context): Boolean {
        val token = ctx.queryParam("token") ?: ctx.header("Authorization")?.removePrefix("Bearer ")
        return token != null && validTokens.contains(token)
    }

    private fun isWsAuthorized(ctx: WsContext): Boolean {
        val token = ctx.queryParam("token")
        return token != null && validTokens.contains(token)
    }

    fun start() {
        app = Javalin.create { config ->
            config.showJavalinBanner = false
        }

        app?.before { ctx ->
            val ip = ctx.ip()
            if (isRateLimited(ip, 60, 60000L, requestCounts)) {
                ctx.status(429)
                ctx.header("Retry-After", "10")
                ctx.result("Too Many Requests")
                return@before
            }

            ctx.header("X-Powered-By", "")
            ctx.header("X-Frame-Options", "DENY")
            ctx.header("X-Content-Type-Options", "nosniff")
            ctx.header("Referrer-Policy", "strict-origin-when-cross-origin")

            val csp = buildString {
                append("default-src 'self'; ")
                append("script-src 'self' 'unsafe-inline'; ")
                append("style-src 'self' 'unsafe-inline'; ")
                append("font-src 'self'; ")
                append("connect-src 'self'; ")
                append("img-src 'self' data:; ")
                append("frame-ancestors 'none';")
            }
            ctx.header("Content-Security-Policy", csp)
        }

        app?.exception(Exception::class.java) { e, ctx ->
            System.err.println("[SERVER_ERROR] Error handling request: ${e.message}")
            ctx.status(500)
            ctx.result("Internal Server Error")
        }

        app?.start(port)

        app?.get("/") { ctx ->
            if (!isAuthorized(ctx)) {
                ctx.status(401)
                ctx.result("Unauthorized. Use ?token= parameter or Authorization: Bearer header.")
                return@get
            }
            ctx.html(getHtmlContent())
        }

        app?.get("/liquid-glass.js") { ctx ->
            if (!isAuthorized(ctx)) {
                ctx.status(401)
                return@get
            }
            val stream = javaClass.getResourceAsStream("/liquid-glass.js")
            if (stream == null) {
                ctx.status(404)
                ctx.result("Not Found")
                return@get
            }
            ctx.contentType("text/javascript; charset=utf-8")
            ctx.header("Cache-Control", "no-store")
            ctx.result(stream.readBytes())
        }

        app?.get("/artwork/{id}") { ctx ->
            if (!isAuthorized(ctx)) {
                ctx.status(401)
                return@get
            }
            val id = ctx.pathParam("id")
            val bytes = appServer.getArtwork(id)
            if (bytes == null || bytes.isEmpty()) {
                ctx.status(404)
                ctx.result("Not Found")
                return@get
            }
            ctx.contentType("image/jpeg")
            ctx.header("Cache-Control", "private, max-age=86400")
            ctx.result(bytes)
        }

        app?.ws("/ws") { ws ->
            ws.onConnect { ctx ->
                if (!isWsAuthorized(ctx)) {
                    ctx.session.close(1008, "Unauthorized")
                    return@onConnect
                }
                connectedClients.add(ctx)
                sendStateToClient(ctx)
            }
            ws.onClose { ctx ->
                connectedClients.remove(ctx)
            }
            ws.onMessage { ctx ->
                val ip = (ctx.session.remoteAddress as? java.net.InetSocketAddress)?.address?.hostAddress ?: "unknown"
                if (isRateLimited(ip, 120, 60000L, wsMessageCounts)) {
                    ctx.send("{\"error\": \"Rate limit exceeded\"}")
                    return@onMessage
                }

                val message = ctx.message()
                if (message.length > 65536) {
                    ctx.session.close(1009, "Message too large")
                    return@onMessage
                }
                try {
                    val jsonEl = Json.parseToJsonElement(message) as? JsonObject
                    val command = jsonEl?.get("command")?.toString()?.replace("\"", "")

                    when (command) {
                        "PLAY" -> appServer.triggerPlay()
                        "PAUSE" -> appServer.triggerPause()
                        "NEXT" -> appServer.triggerNext()
                        "PREVIOUS" -> appServer.triggerPrevious()
                        "SWITCH_ROLE" -> appServer.requestRoleSwitch()
                        "VOLUME" -> {
                            val vol = jsonEl?.get("value")?.toString()?.toIntOrNull()
                            if (vol != null && vol in 0..100) {
                                appServer.triggerVolume(vol)
                            }
                        }
                        "SIMULATE_CALL" -> {
                            val state = jsonEl?.get("state")?.toString()?.replace("\"", "") ?: "RINGING"
                            val callerId = jsonEl?.get("callerId")?.toString()?.replace("\"", "") ?: "Technician Lab"

                            val allowedStates = listOf("RINGING", "OFFHOOK", "IDLE")
                            if (state in allowedStates && callerId.length <= 100) {
                                appServer.simulateCall(state, callerId)
                            }
                        }
                        "SIMULATE_NOTIF" -> {
                            val action = jsonEl?.get("action")?.toString()?.replace("\"", "") ?: "RECEIVE"
                            val id = jsonEl?.get("id")?.toString()?.replace("\"", "") ?: "notif_id"
                            val title = jsonEl?.get("title")?.toString()?.replace("\"", "") ?: "System Update"
                            val text = jsonEl?.get("text")?.toString()?.replace("\"", "") ?: "Optimization applied."

                            val allowedActions = listOf("RECEIVE", "DISMISS")
                            if (action in allowedActions && id.length <= 100 && title.length <= 100 && text.length <= 250) {
                                appServer.simulateNotification(action, id, title, text)
                            }
                        }
                    }

                    broadcastState()
                } catch (e: Exception) {
                    System.err.println("[SERVER_ERROR] Error handling message: ${e.message}")
                }
            }
        }

        scope.launch {
            while (true) {
                try {
                    broadcastState()
                } catch (e: Exception) {
                    System.err.println("[WEB] broadcastState error: ${e.message}")
                }
                delay(1000)
            }
        }

        println("[WEB] Web Dashboard running on http://localhost:$port")
        println("[WEB] Auth token: $authToken")
    }

    private fun getAudioDevices(): List<String> {
        return try {
            javax.sound.sampled.AudioSystem.getMixerInfo()
                .map { it.name }
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getStateJson(): String {
        val isConnected = appServer.isConnected()
        val isOwner = appServer.isAudioOwner()
        val mediaState = appServer.getMediaState()
        val devices = getAudioDevices()

        val notifications = appServer.getNotifications()
        val callState = appServer.getCallState()
        val callerId = appServer.getCallerId()

        val json = buildJsonObject {
            put("connected", isConnected)
            put("isOwner", isOwner)
            if (mediaState != null) {
                put("media", buildJsonObject {
                    put("title", mediaState.title)
                    put("artist", mediaState.artist)
                    put("isPlaying", mediaState.isPlaying)
                    put("position", mediaState.position)
                    put("duration", mediaState.duration)
                    put("appName", mediaState.appName)
                    put("volume", mediaState.volume)
                    put("isMuted", mediaState.isMuted)
                    put("artworkId", mediaState.artworkId ?: "")
                    put("artworkAvailable", mediaState.artworkAvailable)
                })
            } else {
                put("media", buildJsonObject {
                    put("title", "")
                    put("artist", "")
                    put("isPlaying", false)
                    put("position", 0L)
                    put("duration", 0L)
                    put("appName", "")
                    put("volume", 100)
                    put("isMuted", false)
                    put("artworkId", "")
                    put("artworkAvailable", false)
                })
            }
            put("call", buildJsonObject {
                put("state", callState)
                put("callerId", callerId ?: "")
            })
            put(
                "notifications",
                kotlinx.serialization.json.JsonArray(
                    notifications.map { n ->
                        buildJsonObject {
                            put("id", n.id)
                            put("title", n.title)
                            put("text", n.text)
                            put("appName", n.appName)
                            put("packageName", n.packageName)
                            put("action", n.action)
                            put("timestamp", n.timestamp)
                        }
                    }
                )
            )
            put("devices", kotlinx.serialization.json.JsonArray(devices.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        return json.toString()
    }

    private fun sendStateToClient(ctx: WsContext) {
        try {
            ctx.send(getStateJson())
        } catch (e: Exception) {
            System.err.println("[WEB] sendStateToClient error: ${e.message}")
        }
    }

    private fun broadcastState() {
        val state = getStateJson()
        connectedClients.forEach { ctx ->
            if (ctx.session.isOpen) {
                ctx.send(state)
            }
        }
    }

    fun stop() {
        app?.stop()
    }

    private fun getHtmlContent(): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8"/>
    <meta content="width=device-width, initial-scale=1.0" name="viewport"/>
    <title>Synco | Liquid Control Deck</title>
    <style>
        :root {
            --teal: #035352;
            --teal-soft: #0a6f6d;
            --teal-glow: rgba(3, 83, 82, 0.45);
            --yellow: #F3E8BC;
            --yellow-soft: #fbf3d6;
            --ink: #0a0f0f;
            --panel: #0b1213;
            --text: #eef4f0;
            --muted: #7f9b96;
            --radius-xl: 36px;
            --radius-lg: 28px;
            --radius-md: 20px;
            --radius-sm: 14px;
            --sidebar-width: 268px;
            --grad: linear-gradient(135deg, #035352 0%, #0a8a7f 55%, #06b3a2 100%);
            --grad-warm: linear-gradient(135deg, #035352 0%, #7b6a3f 100%);
        }
        * { margin: 0; padding: 0; box-sizing: border-box; }
        html, body { height: 100%; }
        body {
            background:
                radial-gradient(1200px 800px at 12% -10%, rgba(3, 83, 82, 0.55), transparent 60%),
                radial-gradient(1000px 700px at 110% 10%, rgba(243, 232, 188, 0.10), transparent 55%),
                radial-gradient(900px 900px at 50% 120%, rgba(10, 138, 127, 0.28), transparent 60%),
                #080d0d;
            color: var(--text);
            font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            overflow: hidden;
            -webkit-font-smoothing: antialiased;
        }
        /* ---------- Liquid glass material dressing (from liquid-glass GLASS.md) ---------- */
        .glass {
            border-radius: var(--radius-lg);
            background: linear-gradient(180deg, rgba(14, 20, 20, 0.28), rgba(10, 16, 16, 0.42));
            box-shadow:
                0 24px 60px rgba(0, 0, 0, 0.5),
                inset 0 1px 1px rgba(255, 255, 255, 0.18),
                inset 0 -8px 20px rgba(255, 255, 255, 0.05),
                inset 0 0 0 1px rgba(255, 255, 255, 0.10);
            position: relative;
        }
        .glass.glow {
            box-shadow:
                0 24px 60px rgba(0, 0, 0, 0.5),
                0 0 0 1px rgba(3, 83, 82, 0.35),
                0 0 42px -6px var(--teal-glow),
                inset 0 1px 1px rgba(255, 255, 255, 0.18),
                inset 0 -8px 20px rgba(255, 255, 255, 0.05);
        }
        .btn-glass {
            border-radius: var(--radius-sm);
            background: linear-gradient(180deg, rgba(14, 22, 22, 0.35), rgba(8, 14, 14, 0.5));
            box-shadow:
                0 8px 22px rgba(0, 0, 0, 0.4),
                inset 0 1px 1px rgba(255, 255, 255, 0.16),
                inset 0 0 0 1px rgba(255, 255, 255, 0.08);
            color: var(--text);
            border: none;
            cursor: pointer;
            font-family: inherit;
            transition: transform .15s ease, box-shadow .2s ease, background .2s ease;
        }
        .btn-glass:hover { transform: translateY(-2px); box-shadow: 0 12px 28px rgba(0,0,0,.5), inset 0 1px 1px rgba(255,255,255,.2), inset 0 0 0 1px rgba(3,83,82,.6); }
        .btn-glass:active { transform: translateY(0); }
        .btn-primary {
            background: var(--grad);
            color: #f7fff9;
            border-radius: var(--radius-sm);
            border: 1px solid rgba(255,255,255,.18);
            box-shadow: 0 10px 28px var(--teal-glow), inset 0 1px 0 rgba(255,255,255,.25);
        }
        .btn-primary:hover { box-shadow: 0 14px 34px var(--teal-glow), inset 0 1px 0 rgba(255,255,255,.3); transform: translateY(-2px); }
        .btn-warm {
            background: var(--grad-warm);
            color: #fffdf4;
            border: 1px solid rgba(243,232,188,.25);
            box-shadow: 0 10px 26px rgba(243,232,188,.12), inset 0 1px 0 rgba(255,255,255,.2);
        }
        .btn-round {
            width: 54px; height: 54px; border-radius: 50%;
            display: inline-flex; align-items: center; justify-content: center;
            font-size: 20px;
        }
        .glass-chip {
            border-radius: 999px;
            padding: 6px 14px;
            background: rgba(3, 83, 82, 0.35);
            border: 1px solid rgba(3, 138, 127, 0.5);
            color: #bff0e8;
            font-size: 11px;
            font-weight: 700;
            letter-spacing: .12em;
            text-transform: uppercase;
            display: inline-flex; align-items: center; gap: 6px;
        }
        .glass-chip.warm {
            background: rgba(243, 232, 188, 0.12);
            border: 1px solid rgba(243, 232, 188, 0.35);
            color: var(--yellow);
        }
        .section-title {
            font-size: 11px;
            font-weight: 800;
            letter-spacing: .24em;
            text-transform: uppercase;
            color: var(--yellow);
            margin-bottom: 6px;
        }
        .scrollbar::-webkit-scrollbar { width: 5px; height: 5px; }
        .scrollbar::-webkit-scrollbar-track { background: transparent; }
        .scrollbar::-webkit-scrollbar-thumb { background: rgba(3,138,127,.4); border-radius: 10px; }
        input[type=range] { -webkit-appearance: none; appearance: none; width: 100%; height: 5px; border-radius: 10px; background: rgba(127,155,150,.25); outline: none; cursor: pointer; }
        input[type=range]::-webkit-slider-thumb { -webkit-appearance: none; appearance: none; width: 16px; height: 16px; border-radius: 50%; background: var(--yellow); border: 2px solid var(--teal); box-shadow: 0 0 0 4px rgba(243,232,188,.15), 0 4px 12px rgba(0,0,0,.5); cursor: pointer; }
        .layout { display: flex; height: 100vh; }
        /* ---------- Sidebar ---------- */
        .sidebar {
            width: var(--sidebar-width);
            padding: 22px 16px;
            display: flex; flex-direction: column; gap: 6px;
            border-right: 1px solid rgba(255,255,255,.06);
            background: rgba(8,13,13,.55);
            backdrop-filter: blur(18px);
        }
        .brand { display: flex; align-items: center; gap: 12px; padding: 6px 10px 22px; }
        .brand-logo {
            width: 46px; height: 46px; border-radius: 16px;
            background: var(--grad);
            display: grid; place-items: center; font-size: 22px; font-weight: 900; color: #f2fff9;
            box-shadow: 0 10px 26px var(--teal-glow), inset 0 1px 0 rgba(255,255,255,.35);
        }
        .brand-name { font-size: 20px; font-weight: 900; letter-spacing: .02em; }
        .brand-name span { color: var(--yellow); }
        .brand-sub { font-size: 9.5px; letter-spacing: .3em; text-transform: uppercase; color: var(--muted); }
        .nav-item {
            display: flex; align-items: center; gap: 12px;
            padding: 12px 14px; border-radius: var(--radius-sm);
            color: var(--muted); font-size: 13.5px; font-weight: 600;
            cursor: pointer; transition: all .18s ease;
            border: 1px solid transparent;
        }
        .nav-item:hover { color: var(--text); background: rgba(3,83,82,.18); }
        .nav-item.active {
            color: #f2fff9;
            background: linear-gradient(180deg, rgba(3,83,82,.6), rgba(3,83,82,.28));
            border: 1px solid rgba(3,138,127,.5);
            box-shadow: 0 10px 26px var(--teal-glow), inset 0 1px 0 rgba(255,255,255,.15);
        }
        .nav-item .ico { font-size: 18px; width: 22px; text-align: center; }
        .nav-badge { margin-left: auto; background: var(--teal-soft); color: #d9f7f0; font-size: 10px; font-weight: 800; border-radius: 999px; padding: 2px 8px; }
        .nav-badge.warm { background: rgba(243,232,188,.18); color: var(--yellow); }
        /* ---------- Main ---------- */
        .main { flex: 1; overflow-y: auto; padding: 26px 30px 60px; scrollbar-color: rgba(3,138,127,.4) transparent; }
        .topbar { display: flex; align-items: center; gap: 16px; margin-bottom: 26px; }
        .topbar h1 { font-size: 26px; font-weight: 900; }
        .status-pill { margin-left: auto; display: inline-flex; align-items: center; gap: 8px; border-radius: 999px; padding: 8px 16px; font-size: 12px; font-weight: 700; }
        .dot { width: 10px; height: 10px; border-radius: 50%; background: var(--muted); box-shadow: 0 0 0 4px rgba(127,155,150,.15); }
        .dot.on { background: #5ee8c4; box-shadow: 0 0 0 4px rgba(94,232,196,.18), 0 0 14px rgba(94,232,196,.8); }
        .dot.off { background: #ff7b6b; box-shadow: 0 0 0 4px rgba(255,123,107,.18); }
        .grid { display: grid; gap: 18px; }
        .grid-2 { grid-template-columns: 1fr 1fr; }
        .grid-3 { grid-template-columns: 1fr 1fr 1fr; }
        .grid-main { grid-template-columns: 1.7fr 1fr; }
        .view { display: none; }
        .view.active { display: block; }
        .view-enter { animation: fadeUp .35s ease; }
        @keyframes fadeUp { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
        /* Now Playing */
        .artwork {
            width: 100%; aspect-ratio: 1 / 1; border-radius: var(--radius-xl);
            background: var(--grad);
            display: grid; place-items: center;
            box-shadow: 0 30px 60px -12px var(--teal-glow), inset 0 1px 0 rgba(255,255,255,.25);
            overflow: hidden; position: relative;
        }
        .artwork .vinyl { font-size: 88px; filter: drop-shadow(0 12px 22px rgba(0,0,0,.5)); }
        .artwork .spin { animation: spin 8s linear infinite; }
        @keyframes spin { to { transform: rotate(360deg); } }
        .artwork img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; border-radius: var(--radius-xl); }
        .progress-wrap { position: relative; }
        .progress { width: 100%; height: 7px; border-radius: 10px; background: rgba(127,155,150,.22); overflow: hidden; }
        .progress > div { height: 100%; border-radius: 10px; background: var(--grad); box-shadow: 0 0 16px var(--teal-glow); transition: width .6s linear; }
        .progress-time { display: flex; justify-content: space-between; font-size: 11.5px; color: var(--muted); font-variant-numeric: tabular-nums; margin-top: 8px; }
        .big-title { font-size: 30px; font-weight: 900; line-height: 1.1; }
        .big-artist { font-size: 15px; color: var(--muted); margin-top: 6px; }
        .eq-bars { display: inline-flex; align-items: flex-end; gap: 3px; height: 16px; }
        .eq-bars span { width: 3px; background: var(--yellow); border-radius: 3px; animation: eq 1s ease-in-out infinite; }
        .eq-bars span:nth-child(2){ animation-delay:.2s } .eq-bars span:nth-child(3){ animation-delay:.4s }
        @keyframes eq { 0%,100%{height:4px} 50%{height:16px} }
        .notif-row { display: flex; gap: 14px; padding: 12px 0; border-bottom: 1px solid rgba(255,255,255,.05); }
        .notif-row:last-child { border-bottom: none; }
        .notif-ico { width: 42px; height: 42px; border-radius: 12px; background: rgba(3,83,82,.35); display: grid; place-items: center; font-size: 18px; flex-shrink: 0; }
        .log-line { font-family: ui-monospace, 'Cascadia Code', monospace; font-size: 11.5px; line-height: 1.7; color: var(--muted); }
        .log-line .t { color: #8fd8cc; }
        .call-card { text-align: center; padding: 30px 20px; }
        .ring-ring { font-size: 54px; animation: spin 2s linear infinite; display: inline-block; }
        @media (max-width: 900px) {
            body { overflow: auto; }
            .layout { flex-direction: column; height: auto; }
            .sidebar { width: 100%; flex-direction: row; align-items: center; overflow-x: auto; padding: 12px; border-right: none; border-bottom: 1px solid rgba(255,255,255,.06); }
            .brand { padding: 4px 8px; }
            .brand-sub { display: none; }
            .nav-item { white-space: nowrap; }
            .main { padding: 20px 16px; }
            .grid-2, .grid-3, .grid-main { grid-template-columns: 1fr; }
            .topbar h1 { font-size: 20px; }
            .status-pill { padding: 6px 10px; font-size: 10px; }
        }
    </style>
</head>
<body>
<div class="layout">
    <!-- ============ SIDEBAR NAV ============ -->
    <aside class="sidebar">
        <div class="brand">
            <div class="brand-logo">S</div>
            <div>
                <div class="brand-name">Synco<span>.</span></div>
                <div class="brand-sub">Liquid Control Deck</div>
            </div>
        </div>
        <div class="nav-item active" data-view="music">
            <span class="ico">&#9835;</span> Now Playing
            <span class="nav-badge warm" id="nav-playing">--:--</span>
        </div>
        <div class="nav-item" data-view="notifications">
            <span class="ico">&#128276;</span> Notifications
            <span class="nav-badge" id="nav-notif-count">0</span>
        </div>
        <div class="nav-item" data-view="calls">
            <span class="ico">&#128222;</span> Call Center
            <span class="nav-badge" id="nav-call-state">&#9679;</span>
        </div>
        <div class="nav-item" data-view="simulation">
            <span class="ico">&#9881;</span> Simulation Lab
        </div>
        <div class="nav-item" data-view="log">
            <span class="ico">&#128188;</span> Event Log
        </div>
        <div class="nav-item" data-view="system">
            <span class="ico">&#128225;</span> System
        </div>
    </aside>

    <!-- ============ MAIN CONTENT ============ -->
    <main class="main scrollbar">
        <div class="topbar">
            <div>
                <h1 id="page-title">Now Playing</h1>
                <div class="section-title" id="page-sub">Live music sync</div>
            </div>
            <div class="status-pill glass" id="status-pill">
                <span class="dot" id="status-dot"></span>
                <span id="status-text">Disconnected</span>
            </div>
        </div>

        <!-- ====== NOW PLAYING (SPOTIFY-STYLE) ====== -->
        <section class="view active view-enter" id="view-music">
            <div class="grid grid-main">
                <div class="glass glow" style="padding: 22px;">
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px;">
                        <span class="section-title">Now Playing</span>
                        <span class="glass-chip" id="media-app">&#9679; Idle</span>
                    </div>
                    <div class="artwork">
                        <div class="vinyl spin" id="vinyl">&#9835;</div>
                        <img id="art-img" style="display:none;" alt="" onerror="artImgError()"/>
                    </div>
                    <div style="margin-top:22px;">
                        <div style="display:flex;align-items:center;gap:10px;">
                            <div class="big-title" id="media-title">Nothing playing</div>
                            <div class="eq-bars" id="eq" style="display:none;"><span></span><span></span><span></span></div>
                        </div>
                        <div class="big-artist" id="media-artist">Waiting for a track from your phone</div>
                    </div>
                    <div style="margin-top:22px;">
                        <div class="progress-wrap">
                            <div class="progress"><div id="progress-fill" style="width:0%;"></div></div>
                            <div class="progress-time">
                                <span id="time-cur">0:00</span>
                                <span id="time-total">0:00</span>
                            </div>
                        </div>
                    </div>
                    <div style="display:flex;align-items:center;justify-content:center;gap:16px;margin-top:20px;">
                        <button class="btn-glass btn-round" onclick="sendCmd('PREVIOUS')" title="Previous">&#9198;</button>
                        <button class="btn-primary btn-round" id="play-btn" onclick="togglePlay()" style="width:68px;height:68px;font-size:26px;" title="Play/Pause">&#9654;</button>
                        <button class="btn-glass btn-round" onclick="sendCmd('NEXT')" title="Next">&#9197;</button>
                    </div>
                </div>

                <div style="display:flex;flex-direction:column;gap:18px;">
                    <div class="glass" style="padding:22px;">
                        <span class="section-title">Volume</span>
                        <div style="display:flex;align-items:center;gap:14px;margin-top:14px;">
                            <span style="font-size:15px;color:var(--muted);">&#128263;</span>
                            <input type="range" id="volume" min="0" max="100" value="70" oninput="setVolume(this.value)">
                            <span id="vol-display" style="font-weight:800;min-width:44px;text-align:right;">70%</span>
                        </div>
                        <div style="display:flex;gap:10px;margin-top:16px;">
                            <button class="btn-glass btn-primary" style="flex:1;padding:12px;" onclick="togglePlay()" id="play-btn2">&#9654; Play</button>
                        </div>
                    </div>
                    <div class="glass" style="padding:22px;">
                        <span class="section-title">Device</span>
                        <div id="device-list" style="margin-top:12px;display:flex;flex-direction:column;gap:8px;">
                            <div style="color:var(--muted);font-size:13px;">Scanning desktop audio mixers&#8230;</div>
                        </div>
                    </div>
                </div>
            </div>
        </section>

        <!-- ====== NOTIFICATIONS ====== -->
        <section class="view" id="view-notifications">
            <div class="grid grid-2">
                <div class="glass glow" style="padding:22px;">
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px;">
                        <span class="section-title">Phone Notifications</span>
                        <button class="btn-glass" style="padding:8px 14px;font-size:12px;" onclick="clearNotifs()">Clear</button>
                    </div>
                    <div id="notifications-feed" style="max-height:520px;overflow-y:auto;" class="scrollbar">
                        <div style="color:var(--muted);font-size:13px;padding:20px 0;text-align:center;">Waiting for phone notifications&#8230;</div>
                    </div>
                </div>
                <div class="glass" style="padding:22px;">
                    <span class="section-title">Notification Stats</span>
                    <div id="notif-stats" style="margin-top:16px;display:flex;flex-direction:column;gap:12px;">
                        <div style="font-size:13px;color:var(--muted);">Activity will appear here as notifications stream in from your phone.</div>
                    </div>
                </div>
            </div>
        </section>

        <!-- ====== CALL CENTER ====== -->
        <section class="view" id="view-calls">
            <div class="grid grid-2">
                <div class="glass glow call-card" id="call-card">
                    <span class="ring-ring" id="call-ring" style="display:none;">&#128222;</span>
                    <div style="font-size:54px;margin-bottom:6px;" id="call-emoji">&#128221;</div>
                    <div class="section-title" style="color:var(--muted);" id="call-state-label">No Active Call</div>
                    <div class="big-title" style="font-size:22px;margin-top:8px;" id="caller-label">Standby</div>
                    <div style="margin-top:22px;display:flex;gap:12px;justify-content:center;flex-wrap:wrap;">
                        <button class="btn-glass btn-primary" style="padding:12px 22px;" onclick="simulateCall('RINGING','+1 555 0199')">Simulate Ring</button>
                        <button class="btn-glass btn-warm" style="padding:12px 22px;" onclick="simulateCall('OFFHOOK','+1 555 0199')">Answer</button>
                        <button class="btn-glass" style="padding:12px 22px;" onclick="simulateCall('IDLE','')">End Call</button>
                    </div>
                </div>
                <div class="glass" style="padding:22px;">
                    <span class="section-title">Call Console</span>
                    <div style="margin-top:14px;font-size:13px;color:var(--muted);line-height:1.7;">
                        Real incoming calls from your phone surface here and as native Windows toasts. Use the simulation buttons to demo the pipeline end&#8209;to&#8209;end.
                    </div>
                    <div id="call-log" style="margin-top:18px;font-family:ui-monospace,monospace;font-size:12px;color:var(--muted);"></div>
                </div>
            </div>
        </section>

        <!-- ====== SIMULATION LAB ====== -->
        <section class="view" id="view-simulation">
            <div class="grid grid-3">
                <div class="glass glow" style="padding:22px;">
                    <span class="section-title">Transport</span>
                    <p style="font-size:12.5px;color:var(--muted);margin:12px 0 16px;">Drive the active media session on your phone.</p>
                    <div style="display:flex;flex-direction:column;gap:10px;">
                        <button class="btn-glass btn-primary" style="padding:13px;" onclick="sendCmd('PLAY')">&#9654; Play</button>
                        <button class="btn-glass" style="padding:13px;" onclick="sendCmd('PAUSE')">&#10074;&#10074; Pause</button>
                        <button class="btn-glass" style="padding:13px;" onclick="sendCmd('NEXT')">&#9197; Next Track</button>
                        <button class="btn-glass" style="padding:13px;" onclick="sendCmd('PREVIOUS')">&#9198; Previous Track</button>
                    </div>
                </div>
                <div class="glass" style="padding:22px;">
                    <span class="section-title">Audio Role</span>
                    <p style="font-size:12.5px;color:var(--muted);margin:12px 0 16px;">Transfer audio ownership between the phone and this desktop terminal.</p>
                    <div style="display:flex;flex-direction:column;gap:10px;">
                        <button class="btn-glass btn-warm" style="padding:13px;" onclick="sendCmd('SWITCH_ROLE')">&#8646; Switch Audio Role</button>
                        <div id="role-state" class="glass-chip warm" style="justify-content:center;margin-top:6px;">Owner</div>
                    </div>
                </div>
                <div class="glass" style="padding:22px;">
                    <span class="section-title">Simulate</span>
                    <p style="font-size:12.5px;color:var(--muted);margin:12px 0 16px;">Inject test payloads into the live pipeline.</p>
                    <div style="display:flex;flex-direction:column;gap:10px;">
                        <button class="btn-glass btn-primary" style="padding:13px;" onclick="simulateNotification()">&#128276; Sim Notification</button>
                        <button class="btn-glass btn-warm" style="padding:13px;" onclick="simulateCall('RINGING','Technician Lab')">&#128222; Sim Call</button>
                    </div>
                </div>
            </div>
        </section>

        <!-- ====== EVENT LOG ====== -->
        <section class="view" id="view-log">
            <div class="glass glow" style="padding:22px;">
                <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px;">
                    <span class="section-title">Protocol Event Log</span>
                    <button class="btn-glass" style="padding:8px 14px;font-size:12px;" onclick="clearLog()">Clear</button>
                </div>
                <div id="event-log" style="max-height:600px;overflow-y:auto;" class="scrollbar">
                    <div class="log-line">System initialized. Awaiting commands&#8230;</div>
                </div>
            </div>
        </section>

        <!-- ====== SYSTEM ====== -->
        <section class="view" id="view-system">
            <div class="grid grid-2">
                <div class="glass glow" style="padding:22px;">
                    <span class="section-title">Link Status</span>
                    <div style="margin-top:16px;display:flex;flex-direction:column;gap:12px;">
                        <div style="display:flex;justify-content:space-between;font-size:13px;"><span style="color:var(--muted);">Secure Channel</span><span id="sys-channel" class="glass-chip">Idle</span></div>
                        <div style="display:flex;justify-content:space-between;font-size:13px;"><span style="color:var(--muted);">Audio Ownership</span><span id="sys-owner" class="glass-chip warm">Owner</span></div>
                        <div style="display:flex;justify-content:space-between;font-size:13px;"><span style="color:var(--muted);">Auth</span><span id="sys-auth" class="glass-chip">Pending</span></div>
                    </div>
                </div>
                <div class="glass" style="padding:22px;">
                    <span class="section-title">Audio Devices</span>
                    <div id="devices-full" style="margin-top:16px;display:flex;flex-direction:column;gap:8px;font-size:13px;color:var(--muted);">
                        Loading&#8230;
                    </div>
                </div>
            </div>
        </section>
    </main>
</div>

<script src="/liquid-glass.js?token=${'$'}{new URLSearchParams(window.location.search).get('token')}"></script>
<script>
    let ws;
    let isPlaying = false;
    let volume = 70;
    let roleOwner = true;
    const notifs = [];
    const logEl = document.getElementById('event-log');
    const notifFeed = document.getElementById('notifications-feed');

    /* ---------- Liquid glass on every card & button ---------- */
    function initLiquidGlass() {
        document.querySelectorAll('.glass, .btn-glass, .btn-primary, .btn-warm').forEach(function(el){
            if (window.liquidGlass) {
                try { liquidGlass(el, { scale: -96, chroma: 5, blur: 3, saturate: 1.4 }); } catch(e){}
            }
        });
    }
    if (window.liquidGlass) initLiquidGlass();
    else window.addEventListener('load', initLiquidGlass);

    /* ---------- Nav ---------- */
    const titles = {
        music: ['Now Playing', 'Live music sync'],
        notifications: ['Notifications', 'Real-time phone alerts'],
        calls: ['Call Center', 'Incoming & active calls'],
        simulation: ['Simulation Lab', 'Test the sync pipeline'],
        log: ['Event Log', 'Protocol & command history'],
        system: ['System', 'Link & device status']
    };
    document.querySelectorAll('.nav-item').forEach(function(item){
        item.addEventListener('click', function(){
            document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
            document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
            item.classList.add('active');
            const key = item.dataset.view;
            document.getElementById('view-' + key).classList.add('active', 'view-enter');
            document.getElementById('page-title').textContent = titles[key][0];
            document.getElementById('page-sub').textContent = titles[key][1];
        });
    });

    /* ---------- Helpers ---------- */
    function addLog(msg) {
        const div = document.createElement('div');
        div.className = 'log-line';
        div.innerHTML = '<span class="t">&#62;</span> ' + esc(msg);
        logEl.insertBefore(div, logEl.firstChild);
        while (logEl.children.length > 120) logEl.removeChild(logEl.lastChild);
    }
    function clearLog() { logEl.innerHTML = '<div class="log-line"><span class="t">&#62;</span> Log cleared.</div>'; }
    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function(c){
            return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
        });
    }
    function fmt(t) {
        if (!t || t <= 0) return '0:00';
        const s = Math.floor(t / 1000);
        const m = Math.floor(s / 60), ss = s % 60;
        return m + ':' + (ss < 10 ? '0' : '') + ss;
    }
    function fmtTime(t) {
        if (!t || t <= 0) return '--:--';
        const s = Math.floor(t / 1000);
        const m = Math.floor(s / 60), ss = s % 60;
        return m + ':' + (ss < 10 ? '0' : '') + ss;
    }
    const spinner = ['&#10011;','&#10011;','&#10011;'];

    /* ---------- WS ---------- */
    function getWsUrl() {
        const token = new URLSearchParams(window.location.search).get('token');
        return 'ws://' + window.location.host + '/ws?token=' + token;
    }
    function connectWs() {
        ws = new WebSocket(getWsUrl());
        ws.onopen = () => { addLog('WebSocket connected'); setStatus(true); };
        ws.onmessage = (e) => {
            try {
                const data = JSON.parse(e.data);
                if (data.media) updateMedia(data.media);
                if (data.connected !== undefined) setStatus(data.connected);
                if (data.call) updateCall(data.call);
                if (data.notifications) { notifs.length = 0; data.notifications.forEach(n => notifs.push(n)); renderNotifications(); }
                if (data.devices) renderDevices(data.devices);
                if (data.isOwner !== undefined) setOwner(data.isOwner);
            } catch(err) {}
        };
        ws.onclose = () => {
            addLog('WebSocket disconnected, reconnecting in 3s...');
            setStatus(false);
            setTimeout(connectWs, 3000);
        };
        ws.onerror = () => addLog('WebSocket error');
    }
    function setStatus(on) {
        const dot = document.getElementById('status-dot');
        dot.className = 'dot ' + (on ? 'on' : 'off');
        document.getElementById('status-text').textContent = on ? 'Connected' : 'Disconnected';
        document.getElementById('sys-channel').innerHTML = on ? '&#10003; Secure' : '&#9679; Idle';
        document.getElementById('sys-auth').innerHTML = on ? '&#10003; Paired' : 'Pending';
    }
    function setOwner(own) {
        roleOwner = own;
        document.getElementById('role-state').textContent = own ? 'Audio Owner' : 'Remote Controller';
        document.getElementById('sys-owner').textContent = own ? 'Owner' : 'Remote';
    }
    function sendCmd(cmd) {
        if (ws && ws.readyState === WebSocket.OPEN) { ws.send(JSON.stringify({command: cmd})); addLog('Sent: ' + cmd); }
    }
    function sendJson(d) { if (ws && ws.readyState === WebSocket.OPEN) { ws.send(JSON.stringify(d)); addLog('Sent: ' + JSON.stringify(d)); } }
    function togglePlay() {
        sendCmd(isPlaying ? 'PAUSE' : 'PLAY');
        isPlaying = !isPlaying;
        renderPlayBtn();
    }
    function setVolume(val) {
        volume = val;
        document.getElementById('vol-display').textContent = val + '%';
        sendJson({command: 'VOLUME', value: parseInt(val)});
    }

    /* ---------- Media ---------- */
    function updateMedia(m) {
        const hasTrack = m.title && m.title !== 'NO_ACTIVE_MEDIA_SESSION' && m.title !== 'Nothing playing';
        if (hasTrack) {
            document.getElementById('media-title').textContent = m.title;
            document.getElementById('media-artist').textContent = m.artist || 'Unknown artist';
            document.getElementById('media-app').innerHTML = (m.appName ? m.appName : 'Audio') + (m.isPlaying ? ' &#8226; playing' : '');
            document.getElementById('nav-playing').textContent = fmtTime(m.position || 0);
            setArtwork(m.artworkId, m.artworkAvailable);
        } else {
            document.getElementById('media-title').textContent = 'Nothing playing';
            document.getElementById('media-artist').textContent = 'Waiting for a track from your phone';
            document.getElementById('media-app').innerHTML = '&#9679; Idle';
            document.getElementById('nav-playing').textContent = '--:--';
            document.getElementById('time-cur').textContent = '0:00';
            document.getElementById('time-total').textContent = '0:00';
            document.getElementById('progress-fill').style.width = '0%';
            document.getElementById('eq').style.display = 'none';
            document.getElementById('vinyl').classList.remove('spin');
            hideArtwork();
            isPlaying = false; renderPlayBtn();
            return;
        }
        if (m.isPlaying) { document.getElementById('eq').style.display = 'inline-flex'; document.getElementById('vinyl').classList.add('spin'); }
        else { document.getElementById('eq').style.display = 'none'; document.getElementById('vinyl').classList.remove('spin'); }
        isPlaying = !!m.isPlaying;
        renderPlayBtn();
        const pos = m.position || 0, dur = m.duration || 0;
        document.getElementById('time-cur').textContent = fmt(pos);
        document.getElementById('time-total').textContent = fmt(dur);
        document.getElementById('progress-fill').style.width = (dur > 0 ? Math.min(100, (pos / dur) * 100) : 0) + '%';
    }
    let lastArtId = '';
    function setArtwork(id, available) {
        if (!available || !id || id === lastArtId) { if (!available || !id) { hideArtwork(); } return; }
        lastArtId = id;
        const img = document.getElementById('art-img');
        img.onload = function() { img.style.display = 'block'; document.getElementById('vinyl').style.visibility = 'hidden'; };
        img.src = 'artwork/' + encodeURIComponent(id) + '?token=' + encodeURIComponent(getToken());
    }
    function hideArtwork() {
        lastArtId = '';
        const img = document.getElementById('art-img');
        img.style.display = 'none';
        img.removeAttribute('src');
        document.getElementById('vinyl').style.visibility = 'visible';
    }
    function artImgError() {
        hideArtwork();
    }
    function getToken() {
        try {
            const m = location.search.match(/token=([^&]+)/);
            if (m) return m[1];
            const a = location.hash.match(/token=([^&]+)/);
            if (a) return a[1];
        } catch (e) {}
        return '';
    }
    function renderPlayBtn() {
        document.getElementById('play-btn').innerHTML = isPlaying ? '&#10074;&#10074;' : '&#9654;';
        document.getElementById('play-btn2').innerHTML = isPlaying ? '&#10074;&#10074; Pause' : '&#9654; Play';
    }

    /* ---------- Notifications ---------- */
    function renderNotifications() {
        document.getElementById('nav-notif-count').textContent = notifs.length;
        if (notifs.length === 0) {
            notifFeed.innerHTML = '<div style="color:var(--muted);font-size:13px;padding:20px 0;text-align:center;">No phone notifications.</div>';
            document.getElementById('notif-stats').innerHTML = '<div style="font-size:13px;color:var(--muted);">No notifications yet.</div>';
            return;
        }
        const ico = { 'media': '&#9835;', 'message': '&#9993;', 'call': '&#128222;', 'default': '&#128276;' };
        let html = '';
        const latest = notifs.slice().reverse();
        const seen = new Set();
        for (const n of latest) {
            if (seen.has(n.id)) continue;
            seen.add(n.id);
            const app = n.appName || n.packageName || 'App';
            const title = n.title || '';
            const text = n.text || '';
            html += '<div class="notif-row">' +
                '<div class="notif-ico">' + (ico[app.toLowerCase().includes('media')||app.toLowerCase().includes('spotify')||app.toLowerCase().includes('music') ? 'media' : (app.toLowerCase().includes('message')?'message':'default')]) + '</div>' +
                '<div style="flex:1;"><div style="font-size:10px;letter-spacing:.1em;text-transform:uppercase;color:var(--teal-soft);font-weight:700;">' + esc(app) + '</div>' +
                '<div style="color:var(--text);font-weight:700;margin-top:2px;">' + esc(title) + '</div>' +
                '<div style="color:var(--muted);margin-top:2px;font-size:12.5px;">' + esc(text) + '</div></div></div>';
        }
        notifFeed.innerHTML = html;
        document.getElementById('notif-stats').innerHTML =
            '<div style="display:flex;justify-content:space-between;"><span style="color:var(--muted);">Active</span><span style="font-weight:800;color:var(--yellow);">' + notifs.length + '</span></div>' +
            '<div style="display:flex;justify-content:space-between;"><span style="color:var(--muted);">Latest</span><span style="font-weight:800;">' + esc(notifs[notifs.length-1].appName || 'App') + '</span></div>';
    }
    function clearNotifs() { notifs.length = 0; renderNotifications(); addLog('Notifications cleared'); }

    /* ---------- Call ---------- */
    function updateCall(c) {
        const state = (c.state || '').toLowerCase();
        const ringing = ['ringing','incoming','offhook'].includes(state);
        const caller = c.callerId || '';
        document.getElementById('call-ring').style.display = ringing ? 'inline-block' : 'none';
        document.getElementById('call-emoji').textContent = ringing ? '&#128222;' : '&#128221;';
        document.getElementById('call-state-label').textContent = ringing ? 'Active Call' : 'No Active Call';
        document.getElementById('caller-label').textContent = caller || 'Standby';
        document.getElementById('nav-call-state').textContent = ringing ? '\u25CF' : '\u25CB';
        if (ringing) {
            addLog('Call from ' + caller);
            const div = document.createElement('div');
            div.className = 'log-line';
            div.textContent = '> Call: ' + caller + ' [' + state + ']';
            document.getElementById('call-log').appendChild(div);
        }
    }
    function simulateCall(state, callerId) {
        sendJson({command: 'SIMULATE_CALL', state: state, callerId: callerId});
    }

    /* ---------- Devices ---------- */
    function renderDevices(devices) {
        const box = document.getElementById('device-list');
        const full = document.getElementById('devices-full');
        if (!devices || devices.length === 0) {
            box.innerHTML = '<div style="color:var(--muted);font-size:13px;">No audio mixers detected.</div>';
            full.innerHTML = '<div style="color:var(--muted);font-size:13px;">No devices.</div>';
            return;
        }
        box.innerHTML = devices.slice(0,4).map(d => '<div style="display:flex;align-items:center;gap:8px;font-size:12.5px;"><span style="color:var(--teal-soft);">&#9835;</span>' + esc(d) + '</div>').join('');
        full.innerHTML = devices.map(d => '<div style="display:flex;align-items:center;gap:8px;"><span style="color:var(--teal-soft);">&#9835;</span>' + esc(d) + '</div>').join('');
    }

    /* ---------- Sim ---------- */
    function simulateNotification() {
        sendJson({command: 'SIMULATE_NOTIF', action: 'RECEIVE', id: 'sim_' + Date.now(), title: 'Test Notification', text: 'This is a simulated notification from the Synco deck.'});
    }

    /* ---------- Boot ---------- */
    setStatus(false);
    connectWs();
</script>
</body>
</html>
"""
    }
}
