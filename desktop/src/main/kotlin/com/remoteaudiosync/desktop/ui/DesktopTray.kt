package com.remoteaudiosync.desktop.ui

import java.awt.Desktop
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.io.File

/**
 * System-tray integration for the Synco desktop daemon so it can keep running
 * in the background without a visible console window, plus one-click autostart
 * on Windows login (HKCU Run key).
 */
object DesktopTray {

    private var trayIcon: TrayIcon? = null

    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val RUN_VALUE = "SyncoDesktop"

    fun isSupported(): Boolean = SystemTray.isSupported()

    fun install(onQuit: () -> Unit) {
        if (!isSupported()) return
        registerToastShortcut()
        try {
            val tray = SystemTray.getSystemTray()

            val popup = PopupMenu()
            val openItem = MenuItem("Open Dashboard")
            openItem.addActionListener { openDashboard() }
            popup.add(openItem)

            val pinItem = MenuItem("Show PIN")
            pinItem.addActionListener { e -> onPinRequested?.invoke() }
            popup.add(pinItem)

            val autoItem = MenuItem(if (isAutoStartEnabled()) "Disable Autostart" else "Enable Autostart")
            autoItem.addActionListener {
                if (isAutoStartEnabled()) {
                    disableAutoStart()
                    autoItem.label = "Enable Autostart"
                } else {
                    enableAutoStart()
                    autoItem.label = "Disable Autostart"
                }
            }
            popup.add(autoItem)

            popup.addSeparator()

            val quitItem = MenuItem("Quit Synco")
            quitItem.addActionListener { onQuit() }
            popup.add(quitItem)

            val icon = createIcon()
            val ti = TrayIcon(icon, "Synco — Unified Remote Audio & System Sync", popup)
            ti.isImageAutoSize = true
            tray.add(ti)
            trayIcon = ti
            DesktopToastNotifier.attachTrayIcon(ti)

            println("[TRAY] System tray icon active. Right-click for menu.")
        } catch (e: Exception) {
            println("[TRAY] System tray unavailable: ${e.message}")
        }
    }

    var onPinRequested: (() -> Unit)? = null
    var onOpenDashboardRequested: (() -> String?)? = null

    fun remove() {
        try {
            val ti = trayIcon
            if (ti != null) SystemTray.getSystemTray().remove(ti)
            trayIcon = null
        } catch (e: Exception) {
            // best-effort
        }
    }

    fun isInstalled(): Boolean = trayIcon != null

    private fun createIcon(): Image {
        return try {
            // 16x16 simple dot-style icon generated at runtime (no external asset needed).
            val size = 16
            val img = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
            )
            g.color = java.awt.Color(0x08, 0x08, 0x08)
            g.fillRoundRect(0, 0, size - 1, size - 1, 8, 8)
            g.color = java.awt.Color(0xad, 0xc6, 0xff)
            g.fillOval(4, 4, 8, 8)
            g.dispose()
            img
        } catch (e: Exception) {
            val img = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.color = java.awt.Color(0xad, 0xc6, 0xff)
            g.fillRect(0, 0, 16, 16)
            g.dispose()
            img
        }
    }

    private fun openDashboard() {
        val url = onOpenDashboardRequested?.invoke() ?: "http://localhost:8080"
        try {
            Desktop.getDesktop().browse(java.net.URI.create(url))
        } catch (e: Exception) {
            println("[TRAY] Could not open browser: ${e.message}")
        }
    }

    /**
     * Windows 10/11 WinRT toasts require a Start Menu shortcut carrying the app's
     * AUMID, otherwise the OS silently drops them. Register one pointing at the
     * current launcher so native toasts reliably reach the Action Center.
     */
    private fun registerToastShortcut(): Boolean {
        if (!DesktopToastNotifier.isWindows()) return false
        return try {
            val launcher = buildLauncherPath() ?: return false
            val script = readResource("/synco_shortcut.ps1") ?: return false
            val tempScript = File(System.getProperty("java.io.tmpdir"), "synco_shortcut.ps1")
            tempScript.writeText(script)
            val proc = ProcessBuilder(
                "powershell", "-NoProfile", "-STA", "-ExecutionPolicy", "Bypass",
                "-File", tempScript.absolutePath, launcher, File(System.getProperty("user.dir", ".")).absolutePath
            ).start()
            proc.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun readResource(name: String): String? {
        return try {
            val stream = javaClass.getResourceAsStream(name) ?: return null
            stream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildLauncherPath(): String? {
        val cwd = File(System.getProperty("user.dir", "."))
        val candidates = listOf(
            File(cwd, "Synco.bat"),
            File(cwd, "bin/Synco.bat"),
            File(cwd, "bin/Synco")
        )
        for (f in candidates) if (f.exists()) return f.absolutePath
        return null
    }

    /** Autostart on Windows login via HKCU Run key (no admin required). */
    fun enableAutoStart(): Boolean {
        if (!DesktopToastNotifier.isWindows()) {
            println("[AUTOSTART] Only supported on Windows.")
            return false
        }
        val cmd = buildAutoStartCommand()
        if (cmd == null) {
            println("[AUTOSTART] Could not determine a valid launch command.")
            return false
        }
        return try {
            val proc = ProcessBuilder(
                "reg", "add",
                "HKCU\\$RUN_KEY",
                "/v", RUN_VALUE,
                "/t", "REG_SZ",
                "/d", cmd,
                "/f"
            ).start()
            val ok = proc.waitFor() == 0
            println("[AUTOSTART] ${if (ok) "Registered" else "Failed to register"} autostart command.")
            ok
        } catch (e: Exception) {
            println("[AUTOSTART] Failed to register autostart: ${e.message}")
            false
        }
    }

    private fun buildAutoStartCommand(): String? {
        // Prefer the generated Windows launcher for a robust login-time start.
        val launcher = buildLauncherPath()
        if (launcher != null) {
            return "\"$launcher\""
        }

        // Fallback: java -cp <lib jars> com.remoteaudiosync.desktop.MainKt
        val libDir = File(System.getProperty("user.dir", "."), "lib")
        val jars = libDir.listFiles { _, n -> n.endsWith(".jar") }?.sortedBy { it.name } ?: emptyList()
        if (jars.isNotEmpty()) {
            val javaBin = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString()
            val cp = jars.joinToString(";") { it.absolutePath }
            return "\"$javaBin\" -cp \"$cp\" com.remoteaudiosync.desktop.MainKt"
        }
        return null
    }

    fun disableAutoStart(): Boolean {
        return try {
            val proc = ProcessBuilder(
                "reg", "delete",
                "HKCU\\$RUN_KEY",
                "/v", RUN_VALUE,
                "/f"
            ).start()
            proc.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun isAutoStartEnabled(): Boolean {
        return try {
            val proc = ProcessBuilder(
                "reg", "query",
                "HKCU\\$RUN_KEY",
                "/v", RUN_VALUE
            ).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out.contains("SyncoDesktop")
        } catch (e: Exception) {
            false
        }
    }
}
