# Synco 🎧 | Full System Integration & Setup Guide

This guide provides step-by-step instructions for cloning, compiling, deploying, and pairing the **Synco Client** (Android) and **Synco Companion** (Desktop) over **Wi-Fi** or **phone hotspot**.

---

## 📋 Prerequisites

Before starting, ensure your local development environment has the following installed:

- **Java Development Kit (JDK) 17 or 21**
- **Git** (installed and added to your system environment variables)
- **Android Debug Bridge (ADB)** (optional — for direct APK install via USB)
- **Network**: Both devices must be able to reach each other (same Wi-Fi **or** phone hotspot).

---

## 🚀 Step-by-Step Deployment

### 1. Clone the Repository
Open a terminal (Command Prompt, PowerShell, or Git Bash) on your computer and execute:

```bash
git clone https://github.com/dhruvesh07/Synco.git
cd Synco
```

---

### 💻 2. Compile and Start the Desktop Companion

```bash
# Build the distribution
gradlew :desktop:installDist

# Launch the server
.\build\install\Synco\Synco.bat      # Windows
# or: build/install/Synco/bin/Synco  # Linux/macOS
```

Once started, the terminal displays:
```
=================================================================
                     Synco - Desktop Server v1.0.0
=================================================================
[INFO] Booting up subproject environment...
[SERVER] WebSocket Server successfully launched on port 8765
[WEB]  Web Dashboard running on http://localhost:8080
[WEB]  Auth token: <32-char-base64url-token>
[CONSOLE] Interactive Shell ready. Type 'help' to see controls.
[CONSOLE] Listening on Port: 8765 | PIN Code: 742189
```

#### Pairing PIN
- A new random **6-digit PIN** is generated every time the desktop starts.
- You must enter this PIN in the Android app to complete the **PAIR_REQUEST** handshake.
- If the PIN does not match, the server rejects the connection.

#### Web Dashboard (Auth Required)
The desktop also starts a web-based control panel on port **8080**:
- Open your browser and navigate to: **`http://localhost:8080?token=<auth-token>`**
- The **auth token** is a random 32-byte Base64URL string printed once at startup (scroll up if missed).
- **Without the token, the dashboard returns 401 Unauthorized.**
- The dashboard displays connected device status, media controls, role switch, call/notification simulation, and real-time event logs.

#### Desktop Identity Keys
- Persistent Ed25519 identity keys are stored in `desktop_identity.keys.enc`.
- The private key is **AES-256-GCM encrypted** with a key derived from `desktop_identity.key`.
- The raw `desktop_identity.key` is excluded from version control (listed in `.gitignore`).
- These keys enable persistent device recognition across sessions.

---

### 🔍 3. Retrieve Your Desktop's Local IP Address

#### On Windows (PowerShell/Command Prompt):
```powershell
ipconfig
```
Look for the active adapter — either **Wireless LAN adapter Wi-Fi** (router) or **Ethernet adapter** (hotspot connection). Locate the **IPv4 Address** (e.g., `192.168.1.45` or `10.203.177.173`).

#### On Linux / macOS (Terminal):
```bash
ip a | grep inet
# or
ifconfig | grep "inet "
```
Look for your active wireless or ethernet adapter address (excluding `127.0.0.1`).

---

### 📱 4. Build and Install the Android Client

#### Build the APK:
```bash
gradlew :app:assembleDebug
```

The compiled APK will be at:
- `app/build/outputs/apk/debug/app-debug.apk`

#### Install via ADB (USB — recommended for development):
```bash
# Ensure USB debugging is enabled on your phone
adb devices -l
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

#### Install manually (no ADB):
Transfer the `.apk` file to your phone via USB, wireless sharing, or cloud storage. Open the file on your device to install it (enable **Install from unknown sources** if prompted).

---

## 🌐 5. Connection Options

### Option A — Same Wi-Fi Network (Router)
Both devices connected to the same router. Best for home/office use.

```
[Phone] ──── WiFi Router ──── [Desktop]
```

1. Find desktop IP via `ipconfig` (Step 3).
2. Launch the Synco app on your phone.
3. Enter the desktop **IP address** and **Port (8765)**.
4. Enter the **6-digit PIN** from the desktop terminal.
5. Tap **Sync & Connect**.

### Option B — Phone Hotspot (Portable)
Phone acts as the Wi-Fi access point. Desktop connects to phone's hotspot.

```
[Phone] ──── Hotspot ──── [Desktop]
```

1. Enable **Mobile Hotspot** on your phone (Settings → Connections → Mobile Hotspot).
2. Connect your desktop to the phone's hotspot WiFi network.
3. Run `ipconfig` on the desktop — look for the hotspot adapter's IP. It will be in `192.168.x.x` or `10.x.x.x` range.
4. Enter that **IP** and **Port (8765)** in the Synco app on the **same phone**.
5. Enter the **6-digit PIN**.
6. Tap **Sync & Connect**.

> **Note**: Mobile data can remain ON. Synco uses the local hotspot network, not the internet. No mobile data is consumed.

---

## 🤝 6. Securing the Wireless Connection (Both Options)

1. **Launch the Desktop Companion** (Step 2) and note the **6-digit PIN** and **WebSocket port (8765)**.
2. **Launch the Synco App** on your phone.
3. **Grant Permissions** when prompted:
   - **Notification Listening Access**: Allows Synco to stream media playback and notifications.
   - **Phone/Calls Access**: Allows Synco to mirror incoming calls to your desktop.
   - **Bluetooth Connect** (Android 12+): Required for audio device monitoring.
4. **Personalize Your Profile** (optional):
   - Tap the glowing profile circle in the top right to enter Settings.
   - Input your name (e.g., `John Doe`). The dashboard header will display your initials (`JD`).
5. **Connect to Host**:
   - Enter your Desktop's **IPv4 Address** and **Port** (`8765`).
   - Enter the **6-digit PIN** displayed on the desktop terminal.
   - The system performs a secure **X25519 Diffie-Hellman key exchange** with **Ed25519 digital signatures** to derive a shared AES-256-GCM session key.
   - Tap **Sync & Connect**. If the PIN is correct, pairing succeeds and all packets are encrypted end-to-end.
6. **Web Dashboard**:
   - Open `http://localhost:8080?token=<auth-token>` on the desktop browser.
   - The dashboard shows live media telemetry, call/notification logs, and role switch controls.

---

## 🔋 6b. Keeping the Connection Alive in the Background

Synco uses an Android **foreground service** so the connection keeps running even after you **swipe the app away from recents**. However, OEM battery management (especially **Samsung**, but also Xiaomi, OnePlus/OxygenOS, Huawei) can still **kill the process in the background**, which drops the connection until you reopen the app.

> ⚠️ **Samsung users — this step is required.** Without it the connection will not survive backgrounding.

To keep the connection alive:

1. **Open Settings → Apps → Synco → Battery**
2. Set battery usage to **Unrestricted** (disable "Optimize battery usage" / enable "Never sleeping apps").
3. Also enable **Allow background activity** if shown.

On Samsung (recommended):
- **Settings → Battery → Background usage limits → Never sleeping apps → Add Synco.**

**Behavior:**
- After a normal backgrounding (Home button, or swipe from recents), the foreground service keeps the process + WebSocket alive, so the dashboard stays connected.
- If the OS still restarts the process, Synco **auto-reconnects** and **auto-re-pairs** using the stored PIN and last-known server address.
- Only a **Force stop** (long-press → Force stop) fully kills the app — no app can survive that.

---

## 🎮 7. Controls & Usage

### Audio Role System
Synco uses an **ownership model** to determine which device controls media:
- **Desktop as Audio Owner** (default): Play/Pause/Next/Prev/Volume commands execute **on the desktop** (sends keyboard media keys).
- **Phone as Audio Owner** (after role switch): Commands execute **on the phone** (controls phone media players like YouTube, Spotify).

Click **Switch Audio Role** on the dashboard or use the toggle in the app to switch ownership.

### Interactive Shell (Desktop Terminal)
The running desktop terminal accepts commands:
```
play / p         - Send PLAY command
pause / s        - Send PAUSE command
next / n         - Send NEXT track command
prev / r         - Send PREVIOUS track command
volume / v <0-100> - Set volume (e.g. 'v 75')
role             - Switch audio ownership
call <state> <id> - Simulate call (e.g. 'call incoming +15550199')
notif <act> <id> <title> <text> - Simulate notification
help             - Show all commands
exit / q         - Shut down server
```

---

## 🛠️ Development

### Running Tests
```bash
# Android unit tests
gradlew :app:testDebugUnitTest

# Desktop unit tests
gradlew :desktop:test
```

### Building Release APK
```bash
gradlew :app:assembleRelease
```
The release build has **ProGuard** enabled for code obfuscation and minification.

---

## 📁 Project Structure

```
├── app/                         # Android Client
│   ├── src/main/java/
│   │   ├── app/                 # Application class
│   │   ├── artwork/             # Album art caching & downscaling
│   │   ├── crypto/              # AES-GCM, X25519, Ed25519, HKDF
│   │   ├── manager/             # Media, Call, Notification, Bluetooth managers
│   │   ├── network/             # WebSocketClient, ReliableChannel
│   │   ├── protocol/            # Packet serialization, validation, encryption
│   │   ├── service/             # Foreground service, notification listener
│   │   └── ui/                  # Compose screens (Network, Settings, Diagnostics)
│   └── src/test/
│
├── desktop/                     # Desktop Server
│   ├── src/main/kotlin/
│   │   ├── web/                 # Javalin web dashboard
│   │   └── Main.kt              # Server entry point
│   └── build.gradle.kts
│
└── build.gradle.kts             # Root config
```

---

## 🔒 Privacy Notice
Synco is an **offline-first local-network utility**. All WebSocket packets are sent directly between your devices over your local private network. **No remote servers, external APIs, or analytics trackers are used.** Persistent identity keys are encrypted at rest with AES-256-GCM. Your data is entirely yours.
