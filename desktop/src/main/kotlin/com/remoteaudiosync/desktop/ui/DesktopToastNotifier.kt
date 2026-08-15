package com.remoteaudiosync.desktop.ui

import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.File

/**
 * Surfaces real phone notifications and incoming calls as native Windows desktop
 * alerts, matching the Phone Link experience (app name + title + full body).
 *
 * Primary path: the AWT system-tray balloon, which is a true native notification
 * that needs no AUMID registration and always works while the tray icon is present.
 * When a Start Menu shortcut carrying the Synco AUMID is confirmed, a WinRT
 * Action-Center toast is also posted so it appears in the Windows notification
 * center with the app icon.
 */
object DesktopToastNotifier {

    const val APP_ID = "com.remoteaudiosync.synco"
    private const val APP_NAME = "Synco"

    private var trayIcon: TrayIcon? = null
    private var winrtReady: Boolean? = null

    fun attachTrayIcon(icon: TrayIcon?) {
        trayIcon = icon
    }

    fun isWindows(): Boolean {
        return System.getProperty("os.name", "").lowercase().contains("win")
    }

    /**
     * Post a native notification. `title` is the header (app / call name),
     * `text` the body, preserving full context (newlines allowed).
     */
    fun notify(title: String, text: String) {
        if (title.isBlank() && text.isBlank()) return
        val safeTitle = sanitize(title.ifBlank { APP_NAME })
        val safeText = sanitize(text)

        // Guaranteed native notification-area balloon — the reliable Phone-Link-like path.
        showTrayBalloon(safeTitle, safeText)

        // Best-effort Action-Center toast, only when a registered AUMID shortcut exists.
        if (winrtReady == null) winrtReady = isWinrtReady()
        if (winrtReady == true) {
            showNativeToast(safeTitle, safeText)
        }
    }

    private fun sanitize(s: String): String {
        return s.replace("\r", "").trim().take(500)
    }

    /** True when a Start Menu shortcut with the Synco AUMID exists (Action-Center toasts will display). */
    private fun isWinrtReady(): Boolean {
        if (!isWindows()) return false
        val lnk = File(
            System.getProperty("user.home"),
            "AppData/Roaming/Microsoft/Windows/Start Menu/Programs/Synco.lnk"
        )
        return lnk.exists()
    }

    private fun showNativeToast(title: String, text: String) {
        try {
            val script = readScript() ?: return
            val proc = ProcessBuilder(
                "powershell", "-NoProfile", "-STA", "-ExecutionPolicy", "Bypass",
                "-Command", script, title, text
            ).start()
            proc.inputStream.bufferedReader().readText()
            proc.waitFor()
        } catch (e: Exception) {
            // Non-fatal; the tray balloon already covered delivery.
        }
    }

    private fun readScript(): String? {
        return try {
            val stream = javaClass.getResourceAsStream("/synco_toast.ps1") ?: return null
            stream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    private fun showTrayBalloon(title: String, text: String) {
        val icon = trayIcon
        if (icon == null || !SystemTray.isSupported()) return
        try {
            icon.displayMessage(title, text, TrayIcon.MessageType.NONE)
        } catch (e: Exception) {
            // Swallow: tray balloon is best-effort
        }
    }
}
