package com.remoteaudiosync.manager

import android.util.Base64
import com.remoteaudiosync.crypto.CryptoManager
import com.remoteaudiosync.crypto.IdentityKeyStore
import com.remoteaudiosync.network.ReliableChannel
import com.remoteaudiosync.protocol.AuthRequestPayload
import com.remoteaudiosync.protocol.AuthSuccessPayload
import com.remoteaudiosync.protocol.Packet
import com.remoteaudiosync.protocol.PacketType
import com.remoteaudiosync.protocol.PairRequestPayload
import com.remoteaudiosync.protocol.PairResponsePayload
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class PairingManager(
    private val reliableChannel: ReliableChannel,
    private val trustedDeviceManager: TrustedDeviceManager,
    private val identityKeyStore: IdentityKeyStore,
    private val cryptoManager: CryptoManager
) {

    private fun getDeviceName(): String {
        return try {
            val brand = android.os.Build.MANUFACTURER ?: "Unknown"
            val model = android.os.Build.MODEL ?: "Device"
            "$brand $model"
        } catch (e: Exception) {
            "Synco Device"
        }
    }

    suspend fun initiatePairing(pin: String): PairingResult {
        val (pubKeyBytes, privKeyBytes) = identityKeyStore.getOrGenerateIdentityKey()
        cryptoManager.initIdentity(pubKeyBytes, privKeyBytes)

        val pubKeyB64 = Base64.encodeToString(pubKeyBytes, Base64.NO_WRAP)

        val pairRequest = PairRequestPayload(
            deviceName = getDeviceName(),
            publicKey = pubKeyB64,
            pin = pin
        )
        
        val packet = Packet(
            version = 1,
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            senderId = "android-client",
            receiverId = "desktop-server",
            packetType = PacketType.PAIR_REQUEST,
            payload = pairRequest
        )
        
        val pairResponsePacket = withTimeoutOrNull(5000L) {
            coroutineScope {
                val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                    reliableChannel.incomingPackets.first { it.packetType == PacketType.PAIR_RESPONSE }
                }
                reliableChannel.send(packet)
                deferred.await()
            }
        } ?: return PairingResult.Failed("Timeout waiting for PAIR_RESPONSE")
        
        val pairResponsePayload = pairResponsePacket.payload as? PairResponsePayload ?: return PairingResult.Failed("Invalid pair payload type")
        
        if (pairResponsePayload.status != "ACCEPTED" && pairResponsePayload.status != "SUCCESS") {
            return PairingResult.Failed("Pairing rejected: ${pairResponsePayload.status}")
        }
        
        val remoteIdentityPublicKey = Base64.decode(pairResponsePayload.publicKey, Base64.NO_WRAP)
        
        // Generate ephemeral key
        cryptoManager.generateEphemeralKeyPair()
        val ephemeralPublicKeyBytes = cryptoManager.ephemeralPublicKeyBytes ?: return PairingResult.Failed("Failed to generate ephemeral key")
        
        // Sign ephemeral public key
        val signatureBytes = cryptoManager.sign(ephemeralPublicKeyBytes)
        
        val authRequest = AuthRequestPayload(
            ephemeralPublicKey = Base64.encodeToString(ephemeralPublicKeyBytes, Base64.NO_WRAP),
            signature = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
        )
        
        val authPacket = Packet(
            version = 1,
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            senderId = "android-client",
            receiverId = pairResponsePacket.senderId,
            packetType = PacketType.AUTH_REQUEST,
            payload = authRequest
        )
        
        val authResponsePacket = withTimeoutOrNull(5000L) {
            coroutineScope {
                val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                    reliableChannel.incomingPackets.first { it.packetType == PacketType.AUTH_SUCCESS }
                }
                reliableChannel.send(authPacket)
                deferred.await()
            }
        } ?: return PairingResult.Failed("Timeout waiting for AUTH_SUCCESS")
        
        val authSuccessPayload = authResponsePacket.payload as? AuthSuccessPayload ?: return PairingResult.Failed("Invalid auth payload type")
        
        val remoteEphemeralPubKey = Base64.decode(authSuccessPayload.ephemeralPublicKey, Base64.NO_WRAP)
        val remoteSignature = Base64.decode(authSuccessPayload.signature, Base64.NO_WRAP)
        
        if (!cryptoManager.verifySignature(remoteIdentityPublicKey, remoteEphemeralPubKey, remoteSignature)) {
            return PairingResult.Failed("Invalid signature on AUTH_SUCCESS")
        }
        
        cryptoManager.deriveSessionKey(remoteEphemeralPubKey)
        
        trustedDeviceManager.saveTrustedDevice(pairResponsePacket.senderId, pairResponsePayload.publicKey)
        // Remember the PIN so we can transparently re-pair after a drop / reconnect.
        trustedDeviceManager.savePairPin(pin)
        
        return PairingResult.Success
    }

    /**
     * Re-runs the full pairing handshake after the transport reconnects, using a PIN that
     * was already accepted for this trusted device. This restores the secure, authenticated
     * channel automatically so media commands (play/next/prev) keep working without the user
     * having to re-enter the PIN.
     */
    suspend fun autoReconnect(pin: String): PairingResult {
        return initiatePairing(pin)
    }
}

sealed class PairingResult {
    object Success : PairingResult()
    data class Failed(val reason: String) : PairingResult()
}
