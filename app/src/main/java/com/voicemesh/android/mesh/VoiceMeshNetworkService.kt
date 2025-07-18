package com.voicemesh.android.mesh

import android.content.Context
import android.util.Log
import com.voicemesh.android.core.VoiceMeshNetworkDelegate
import com.voicemesh.android.core.VoiceFragmentManager
import com.voicemesh.android.model.EphemeralVoiceMessage
import com.voicemesh.android.model.VoicePeer
import com.voicemesh.android.model.VoiceFragment
import com.voicemesh.android.protocol.VoiceMessageType
import com.voicemesh.android.protocol.VoicePacket
import com.voicemesh.android.protocol.VoiceProtocolResult
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * VoiceMesh network service extending BLE mesh for voice messages
 * Builds on BitChat's proven mesh architecture with voice-specific extensions
 */
class VoiceMeshNetworkService(private val context: Context) {
    
    companion object {
        private const val TAG = "VoiceMeshNetworkService"
        private const val MAX_TTL: UByte = 7u
        private const val PEER_ID_LENGTH = 16
        private const val VOICE_SERVICE_UUID = "VoiceMesh-2024"
    }
    
    // My peer identification - compatible with BitChat format
    private val myPeerID: String = generateCompatiblePeerID()
    
    // Core components
    private val fragmentManager = VoiceFragmentManager()
    private val bleNetworkManager = VoiceBLEManager(context, myPeerID)
    
    // Service state
    private var isInitialized = false
    
    // Connected peers with voice capabilities
    private val voicePeers = ConcurrentHashMap<String, VoicePeer>()
    
    // Network delegate
    var delegate: VoiceMeshNetworkDelegate? = null
    
    // Service scope
    private val networkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        setupBLECallbacks()
        setupFragmentCallbacks()
    }
    
    /**
     * Initialize the voice mesh network service
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true
        
        try {
            // Initialize BLE networking
            val bleInitialized = bleNetworkManager.initialize()
            if (!bleInitialized) {
                Log.e(TAG, "Failed to initialize BLE networking")
                return@withContext false
            }
            
            // Start advertising voice capabilities
            bleNetworkManager.startAdvertising(createVoiceCapabilityAdvertisement())
            
            // Start scanning for voice peers
            bleNetworkManager.startScanning()
            
            isInitialized = true
            Log.d(TAG, "VoiceMesh network service initialized")
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize network service", e)
            return@withContext false
        }
    }
    
    /**
     * Send voice message to specific peer
     */
    suspend fun sendVoiceMessage(message: EphemeralVoiceMessage, recipientPeerID: String): Boolean = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.e(TAG, "Network service not initialized")
            return@withContext false
        }
        
        try {
            // Create fragments from the voice message
            val fragments = fragmentManager.fragmentVoiceMessage(message)
            if (fragments.isEmpty()) {
                Log.e(TAG, "Failed to create fragments for message ${message.id}")
                return@withContext false
            }
            
            Log.d(TAG, "Sending voice message ${message.id} in ${fragments.size} fragments to $recipientPeerID")
            
            // Send each fragment
            var sentFragments = 0
            for (fragment in fragments) {
                val voicePacket = createVoicePacket(fragment, recipientPeerID)
                
                if (bleNetworkManager.sendPacket(voicePacket, recipientPeerID)) {
                    sentFragments++
                    // Small delay between fragments to avoid overwhelming BLE
                    delay(50)
                } else {
                    Log.w(TAG, "Failed to send fragment ${fragment.fragmentIndex} of message ${message.id}")
                }
            }
            
            val success = sentFragments == fragments.size
            Log.d(TAG, "Voice message ${message.id}: sent $sentFragments/${fragments.size} fragments")
            
            return@withContext success
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send voice message ${message.id}", e)
            return@withContext false
        }
    }
    
    /**
     * Send voice message deletion command
     */
    suspend fun sendVoiceDeletion(messageID: String, recipientPeerID: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val deletePacket = VoicePacket(
                type = VoiceMessageType.VOICE_MESSAGE_DELETE,
                messageID = messageID,
                senderID = myPeerID.toByteArray(),
                recipientID = recipientPeerID.toByteArray(),
                fragmentData = byteArrayOf(),
                timestamp = System.currentTimeMillis().toULong(),
                ttl = MAX_TTL
            )
            
            return@withContext bleNetworkManager.sendPacket(deletePacket, recipientPeerID)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send voice deletion for $messageID", e)
            return@withContext false
        }
    }
    
    /**
     * Send delivery confirmation
     */
    suspend fun sendDeliveryConfirmation(messageID: String, senderPeerID: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val ackPacket = VoicePacket(
                type = VoiceMessageType.VOICE_MESSAGE_DELIVERED,
                messageID = messageID,
                senderID = myPeerID.toByteArray(),
                recipientID = senderPeerID.toByteArray(),
                fragmentData = byteArrayOf(),
                timestamp = System.currentTimeMillis().toULong(),
                ttl = MAX_TTL
            )
            
            return@withContext bleNetworkManager.sendPacket(ackPacket, senderPeerID)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send delivery confirmation for $messageID", e)
            return@withContext false
        }
    }
    
    /**
     * Get my peer ID
     */
    fun getMyPeerID(): String = myPeerID
    
    /**
     * Get connected voice peers
     */
    fun getConnectedVoicePeers(): List<VoicePeer> {
        return voicePeers.values.filter { it.isOnline && it.voiceCapable }
    }
    
    /**
     * Get peer by ID
     */
    fun getVoicePeer(peerID: String): VoicePeer? {
        return voicePeers[peerID]
    }
    
    /**
     * Create voice packet from fragment
     */
    private fun createVoicePacket(fragment: VoiceFragment, recipientPeerID: String): VoicePacket {
        return VoicePacket(
            type = VoiceMessageType.VOICE_MESSAGE_FRAGMENT,
            messageID = fragment.messageID,
            senderID = myPeerID.toByteArray(),
            recipientID = recipientPeerID.toByteArray(),
            fragmentData = fragment.audioData,
            timestamp = System.currentTimeMillis().toULong(),
            ttl = MAX_TTL,
            fragmentIndex = fragment.fragmentIndex,
            totalFragments = fragment.totalFragments
        )
    }
    
    /**
     * Handle incoming voice packet
     */
    private fun handleIncomingVoicePacket(packet: VoicePacket, senderPeer: VoicePeer) {
        when (packet.type) {
            VoiceMessageType.VOICE_MESSAGE_FRAGMENT -> {
                handleVoiceFragment(packet, senderPeer)
            }
            VoiceMessageType.VOICE_MESSAGE_DELETE -> {
                handleVoiceDeletion(packet, senderPeer)
            }
            VoiceMessageType.VOICE_MESSAGE_DELIVERED -> {
                handleDeliveryConfirmation(packet, senderPeer)
            }
            else -> {
                Log.d(TAG, "Unhandled voice packet type: ${packet.type}")
            }
        }
    }
    
    /**
     * Handle voice message fragment
     */
    private fun handleVoiceFragment(packet: VoicePacket, senderPeer: VoicePeer) {
        try {
            val fragment = VoiceFragment(
                messageID = packet.messageID,
                fragmentIndex = packet.fragmentIndex,
                totalFragments = packet.totalFragments,
                audioData = packet.fragmentData,
                checksum = calculateChecksum(packet.fragmentData)
            )
            
            // Add fragment to manager
            fragmentManager.addFragment(fragment)
            
            Log.d(TAG, "Received voice fragment ${fragment.fragmentIndex}/${fragment.totalFragments} " +
                      "for message ${fragment.messageID} from ${senderPeer.nickname}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle voice fragment from ${senderPeer.peerID}", e)
        }
    }
    
    /**
     * Handle voice message deletion
     */
    private fun handleVoiceDeletion(packet: VoicePacket, senderPeer: VoicePeer) {
        Log.d(TAG, "Received voice deletion command for message ${packet.messageID} from ${senderPeer.nickname}")
        // The delegate should handle the actual deletion
        // This is mainly for network-wide cleanup coordination
    }
    
    /**
     * Handle delivery confirmation
     */
    private fun handleDeliveryConfirmation(packet: VoicePacket, senderPeer: VoicePeer) {
        Log.d(TAG, "Received delivery confirmation for message ${packet.messageID} from ${senderPeer.nickname}")
        delegate?.onVoiceMessageDelivered(packet.messageID, senderPeer)
    }
    
    /**
     * Calculate SHA-256 checksum for fragment data
     */
    private fun calculateChecksum(data: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }
    
    /**
     * Create voice capability advertisement
     */
    private fun createVoiceCapabilityAdvertisement(): ByteArray {
        // Simple advertisement indicating voice mesh capability
        val serviceData = "VoiceMesh-1.0".toByteArray()
        val peerIDData = myPeerID.toByteArray().take(8).toByteArray() // Truncate for BLE
        
        return serviceData + peerIDData
    }
    
    /**
     * Generate compatible peer ID (similar to BitChat format)
     */
    private fun generateCompatiblePeerID(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..PEER_ID_LENGTH)
            .map { chars.random() }
            .joinToString("")
    }
    
    /**
     * Set up BLE manager callbacks
     */
    private fun setupBLECallbacks() {
        bleNetworkManager.onPeerConnected = { peerID, advertisementData ->
            handlePeerConnected(peerID, advertisementData)
        }
        
        bleNetworkManager.onPeerDisconnected = { peerID ->
            handlePeerDisconnected(peerID)
        }
        
        bleNetworkManager.onPacketReceived = { packet, senderPeerID ->
            handleIncomingPacket(packet, senderPeerID)
        }
        
        bleNetworkManager.onNetworkError = { error ->
            delegate?.onNetworkError(error)
        }
    }
    
    /**
     * Set up fragment manager callbacks
     */
    private fun setupFragmentCallbacks() {
        fragmentManager.setMessageReassembledCallback { messageID, audioData ->
            handleCompleteVoiceMessage(messageID, audioData)
        }
        
        fragmentManager.setReassemblyFailedCallback { messageID, error ->
            Log.w(TAG, "Voice message reassembly failed for $messageID: $error")
            delegate?.onReassemblyError(messageID, error)
        }
    }
    
    /**
     * Handle peer connection
     */
    private fun handlePeerConnected(peerID: String, advertisementData: ByteArray) {
        // Check if this is a voice-capable peer
        val isVoiceCapable = advertisementData.let { data ->
            String(data).contains("VoiceMesh", ignoreCase = true)
        }
        
        if (isVoiceCapable) {
            val voicePeer = VoicePeer(
                peerID = peerID,
                nickname = "VoicePeer-${peerID.take(8)}", // Generate display name
                fingerprint = peerID, // Simplified for now
                voiceCapable = true,
                lastSeen = System.currentTimeMillis(),
                isOnline = true
            )
            
            voicePeers[peerID] = voicePeer
            delegate?.onPeerConnected(voicePeer)
            
            Log.d(TAG, "Voice-capable peer connected: ${voicePeer.nickname}")
        }
    }
    
    /**
     * Handle peer disconnection
     */
    private fun handlePeerDisconnected(peerID: String) {
        voicePeers[peerID]?.let { peer ->
            voicePeers[peerID] = peer.copy(isOnline = false, lastSeen = System.currentTimeMillis())
            delegate?.onPeerDisconnected(peerID)
            Log.d(TAG, "Voice peer disconnected: ${peer.nickname}")
        }
    }
    
    /**
     * Handle incoming packet
     */
    private fun handleIncomingPacket(packet: Any, senderPeerID: String) {
        when (packet) {
            is VoicePacket -> {
                voicePeers[senderPeerID]?.let { senderPeer ->
                    handleIncomingVoicePacket(packet, senderPeer)
                }
            }
            else -> {
                Log.d(TAG, "Non-voice packet received from $senderPeerID")
            }
        }
    }
    
    /**
     * Handle complete voice message assembly
     */
    private fun handleCompleteVoiceMessage(messageID: String, audioData: ByteArray) {
        try {
            // Parse the complete voice message
            val voiceMessage = EphemeralVoiceMessage.fromBinary(audioData)
            if (voiceMessage != null) {
                val senderPeer = voicePeers[voiceMessage.senderID]
                if (senderPeer != null) {
                    delegate?.onVoiceMessageReceived(voiceMessage)
                    
                    // Send delivery confirmation
                    networkScope.launch {
                        sendDeliveryConfirmation(messageID, voiceMessage.senderID)
                    }
                    
                    Log.d(TAG, "Complete voice message received: $messageID from ${senderPeer.nickname}")
                } else {
                    Log.w(TAG, "Received voice message from unknown peer: ${voiceMessage.senderID}")
                }
            } else {
                Log.e(TAG, "Failed to parse complete voice message: $messageID")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle complete voice message $messageID", e)
        }
    }
    
    /**
     * Release all resources
     */
    fun release() {
        fragmentManager.release()
        bleNetworkManager.release()
        networkScope.cancel()
        voicePeers.clear()
        isInitialized = false
        Log.d(TAG, "VoiceMesh network service released")
    }
}

/**
 * Voice BLE manager - simplified version of BitChat's BLE management
 * Focuses on voice message transmission requirements
 */
class VoiceBLEManager(
    private val context: Context,
    private val myPeerID: String
) {
    companion object {
        private const val TAG = "VoiceBLEManager"
    }
    
    // BLE callbacks
    var onPeerConnected: ((String, ByteArray) -> Unit)? = null
    var onPeerDisconnected: ((String) -> Unit)? = null
    var onPacketReceived: ((Any, String) -> Unit)? = null
    var onNetworkError: ((String) -> Unit)? = null
    
    // Mock implementation for demonstration
    // In a real implementation, this would use Android BLE APIs
    private var isInitialized = false
    private val mockPeers = mutableSetOf<String>()
    
    fun initialize(): Boolean {
        // Mock successful initialization
        isInitialized = true
        Log.d(TAG, "BLE manager initialized (mock)")
        
        // Simulate finding some peers after a delay
        simulatePeerDiscovery()
        
        return true
    }
    
    fun startAdvertising(advertisementData: ByteArray): Boolean {
        if (!isInitialized) return false
        Log.d(TAG, "Started advertising voice capabilities")
        return true
    }
    
    fun startScanning(): Boolean {
        if (!isInitialized) return false
        Log.d(TAG, "Started scanning for voice peers")
        return true
    }
    
    fun sendPacket(packet: VoicePacket, recipientPeerID: String): Boolean {
        if (!isInitialized) return false
        
        // Mock successful packet sending
        Log.d(TAG, "Sent voice packet to $recipientPeerID (mock)")
        return true
    }
    
    private fun simulatePeerDiscovery() {
        // Simulate finding peers after initialization
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            delay(2000) // Wait 2 seconds
            
            // Simulate 2-3 mock peers
            val mockPeerCount = Random.nextInt(2, 4)
            repeat(mockPeerCount) { i ->
                val peerID = "MockPeer${i + 1}-${Random.nextInt(1000, 9999)}"
                val advertisementData = "VoiceMesh-1.0-Mock".toByteArray()
                
                mockPeers.add(peerID)
                onPeerConnected?.invoke(peerID, advertisementData)
                
                delay(500) // Stagger peer discoveries
            }
        }
    }
    
    fun release() {
        mockPeers.clear()
        isInitialized = false
        Log.d(TAG, "BLE manager released")
    }
} 