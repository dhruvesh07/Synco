package com.remoteaudiosync.desktop

import com.remoteaudiosync.protocol.MediaStatePayload
import java.io.BufferedReader
import java.io.InputStreamReader

interface DesktopMediaSessionObserver {
    fun startObserving(callback: (MediaStatePayload?) -> Unit)
    fun stopObserving()
    fun isSupported(): Boolean
    fun getCurrentState(): MediaStatePayload?
}

class DefaultDesktopMediaSessionObserver : DesktopMediaSessionObserver {
    private var callback: ((MediaStatePayload?) -> Unit)? = null
    private var isRunning = false
    private val osName = System.getProperty("os.name")?.lowercase() ?: ""

    override fun startObserving(callback: (MediaStatePayload?) -> Unit) {
        this.callback = callback
        this.isRunning = true
    }

    override fun stopObserving() {
        this.callback = null
        this.isRunning = false
    }

    override fun isSupported(): Boolean {
        if (osName.contains("linux")) {
            return isCommandAvailable("playerctl")
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return true
        }
        if (osName.contains("win")) {
            return true
        }
        return false
    }

    override fun getCurrentState(): MediaStatePayload? {
        if (!isSupported()) return null
        
        try {
            if (osName.contains("linux")) {
                val title = executeCommand(listOf("playerctl", "metadata", "title"))?.trim()
                if (title.isNullOrEmpty()) return null
                val artist = executeCommand(listOf("playerctl", "metadata", "artist"))?.trim() ?: "Unknown"
                val appName = executeCommand(listOf("playerctl", "metadata", "mpris:trackid"))?.trim()?.split("/")?.getOrNull(3) ?: "Unknown"
                val status = executeCommand(listOf("playerctl", "status"))?.trim()
                val isPlaying = status == "Playing"
                
                val positionUs = executeCommand(listOf("playerctl", "position"))?.trim()?.toDoubleOrNull() ?: 0.0
                val position = (positionUs * 1000).toLong()
                
                val durationStr = executeCommand(listOf("playerctl", "metadata", "mpris:length"))?.trim()
                val duration = (durationStr?.toLongOrNull() ?: -1L) / 1000
                
                val volumeStr = executeCommand(listOf("playerctl", "volume"))?.trim()
                val volume = ((volumeStr?.toDoubleOrNull() ?: 1.0) * 100).toInt()
                
                return MediaStatePayload(
                    title = title,
                    artist = artist,
                    isPlaying = isPlaying,
                    position = position,
                    duration = duration,
                    appName = appName,
                    volume = volume,
                    isMuted = volume == 0
                )
            } else if (osName.contains("mac") || osName.contains("darwin")) {
                val isMusicRunning = executeCommand(listOf("osascript", "-e", "application \"Music\" is running"))?.trim() == "true"
                if (isMusicRunning) {
                    val title = executeCommand(listOf("osascript", "-e", "tell application \"Music\" to name of current track"))?.trim() ?: ""
                    val artist = executeCommand(listOf("osascript", "-e", "tell application \"Music\" to artist of current track"))?.trim() ?: ""
                    val playerState = executeCommand(listOf("osascript", "-e", "tell application \"Music\" to player state as string"))?.trim()
                    val isPlaying = playerState == "playing"
                    val positionSec = executeCommand(listOf("osascript", "-e", "tell application \"Music\" to player position"))?.trim()?.toDoubleOrNull() ?: 0.0
                    val position = (positionSec * 1000).toLong()
                    val durationSec = executeCommand(listOf("osascript", "-e", "tell application \"Music\" to duration of current track"))?.trim()?.toDoubleOrNull() ?: 0.0
                    val duration = (durationSec * 1000).toLong()
                    
                    val volumeStr = executeCommand(listOf("osascript", "-e", "output volume of (get volume settings)"))?.trim()
                    val volume = volumeStr?.toIntOrNull() ?: 100
                    val isMuted = executeCommand(listOf("osascript", "-e", "output muted of (get volume settings)"))?.trim() == "true"
                    
                    return MediaStatePayload(
                        title = title,
                        artist = artist,
                        isPlaying = isPlaying,
                        position = position,
                        duration = duration,
                        appName = "Music",
                        volume = volume,
                        isMuted = isMuted
                    )
                }
            } else if (osName.contains("win")) {
                val script = "[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media, ContentType = WindowsRuntime] | Out-Null; \$manager = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync().GetAwaiter().GetResult(); \$session = \$manager.GetCurrentSession(); if (\$session -ne \$null) { \$info = \$session.TryGetMediaPropertiesAsync().GetAwaiter().GetResult(); \$playback = \$session.GetPlaybackInfo(); Write-Output \"\$(\$info.Title)|\$(\$info.Artist)|\$(\$playback.PlaybackStatus)\" }"
                val output = executeCommand(listOf("powershell", "-NoProfile", "-Command", script))?.trim()
                if (output != null && output.contains("|")) {
                    val parts = output.split("|")
                    if (parts.size >= 3 && parts[0].isNotBlank()) {
                        return MediaStatePayload(
                            title = parts[0].takeIf { it.isNotBlank() } ?: "Unknown",
                            artist = parts[1].takeIf { it.isNotBlank() } ?: "Unknown",
                            isPlaying = parts[2] == "Playing",
                            position = 0,
                            duration = -1,
                            appName = "Windows Media",
                            volume = 100,
                            isMuted = false
                        )
                    }
                }
                // No real Windows media session: report a clean idle state instead of fabricating a
                // fake "Desktop Audio / Playing" track, so the dashboard honestly shows nothing playing.
                return null
            }
        } catch (e: Exception) {
            // Graceful error fallback
        }
        return null
    }

    private fun isCommandAvailable(cmd: String): Boolean {
        return try {
            val process = ProcessBuilder(cmd, "--version").start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun executeCommand(cmd: List<String>): String? {
        return try {
            val process = ProcessBuilder(cmd).start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            null
        }
    }
}
