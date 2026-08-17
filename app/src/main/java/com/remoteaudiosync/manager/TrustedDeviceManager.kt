package com.remoteaudiosync.manager

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TrustedDeviceManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "trusted_devices_encrypted",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveTrustedDevice(deviceId: String, publicKey: String) {
        prefs.edit().putString(deviceId, publicKey).apply()
    }

    fun getTrustedDevicePublicKey(deviceId: String): String? {
        return prefs.getString(deviceId, null)
    }

    fun isTrusted(deviceId: String): Boolean {
        return prefs.contains(deviceId)
    }

    // Store the pairing PIN (encrypted) so the client can transparently re-authenticate
    // after the WebSocket drops / reconnects, without forcing the user to re-enter it.
    fun savePairPin(pin: String) {
        prefs.edit().putString("last_pair_pin", pin).apply()
    }

    fun getPairPin(): String? {
        return prefs.getString("last_pair_pin", null)
    }

    fun hasStoredPin(): Boolean {
        return prefs.contains("last_pair_pin")
    }

    fun clearPairPin() {
        prefs.edit().remove("last_pair_pin").apply()
    }
}
