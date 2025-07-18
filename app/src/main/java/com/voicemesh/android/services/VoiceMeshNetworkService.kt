package com.voicemesh.android.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.voicemesh.android.R
import com.voicemesh.android.model.VoicePeer
import com.voicemesh.android.protocol.VoicePacket
import com.voicemesh.android.protocol.VoiceMessageType
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * VoiceMesh networking service - BLE mesh networking for voice messages
 * Based on BitChat architecture with mock implementation for development
 */
class VoiceMeshNetworkService : Service() {
    
    companion object {
        private const val TAG = "VoiceMeshNetworkService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voicemesh_network"
        private const val CHANNEL_NAME = "VoiceMesh Network"
        
        // Mock network parameters
        private const val MOCK_PEER_DISCOVERY_INTERVAL = 15000L // 15 seconds
        private const val MOCK_MESSAGE_DELAY_MIN = 100L // 100ms
        private const val MOCK_MESSAGE_DELAY_MAX = 2000L // 2 seconds
        private const val MOCK_DELIVERY_SUCCESS_RATE = 0.85f // 85% success rate
        private const val MAX_CONCURRENT_PEERS = 20
    }
    
    // Service binder
    inner class VoiceMeshBinder : Binder() {
        fun getService(): VoiceMeshNetworkService = this@VoiceMeshNetworkService
    }
    
    private val binder = VoiceMeshBinder()
    
    // Network state
    private var isNetworkActive = false
    private val connectedPeers = ConcurrentHashMap<String, VoicePeer>()
    private val pendingTransmissions = ConcurrentHashMap<String, VoicePacket>()
    
    // Mock peer simulation
    private val mockPeers = listOf(
        "Alice", "Bob", "Charlie", "Diana", "Eve", "Frank", "Grace", "Henry"
    )
    
    // Coroutine scope
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Network callbacks
    var onPeerDiscovered: ((VoicePeer) -> Unit)? = null
    var onPeerLost: ((String) -> Unit)? = null
    var onPacketReceived: ((VoicePacket) -> Unit)? = null
    var onTransmissionComplete: ((String, Boolean) -> Unit)? = null
    var onNetworkStateChanged: ((Boolean) -> Unit)? = null
    
    // Background jobs
    private var discoveryJob: Job? = null
    private var maintenanceJob: Job? = null
    
    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "Service bound")
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VoiceMeshNetworkService created")
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Starting VoiceMesh network service")
        
        startForeground(NOTIFICATION_ID, createNotification())
        startNetworking()
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "VoiceMeshNetworkService destroyed")
        stopNetworking()
        serviceScope.cancel()
    }
    
    /**
     * Start BLE mesh networking
     */
    fun startNetworking() {
        if (isNetworkActive) {
            Log.w(TAG, "Network already active")
            return
        }
        
        Log.i(TAG, "Starting VoiceMesh networking")
        isNetworkActive = true
        
        // Start mock peer discovery
        startMockPeerDiscovery()
        
        // Start network maintenance
        startNetworkMaintenance()
        
        onNetworkStateChanged?.invoke(true)
        updateNotification("VoiceMesh Active - Discovering peers")
    }
    
    /**
     * Stop BLE mesh networking
     */
    fun stopNetworking() {
        if (!isNetworkActive) {
            Log.w(TAG, "Network already stopped")
            return
        }
        
        Log.i(TAG, "Stopping VoiceMesh networking")
        isNetworkActive = false
        
        // Stop background jobs
        discoveryJob?.cancel()
        maintenanceJob?.cancel()
        
        // Clear connected peers
        connectedPeers.clear()
        pendingTransmissions.clear()
        
        onNetworkStateChanged?.invoke(false)
        updateNotification("VoiceMesh Stopped")
    }
    
    /**
     * Send voice packet over mesh network
     */
    fun sendPacket(packet: VoicePacket): Boolean {
        if (!isNetworkActive) {
            Log.w(TAG, "Cannot send packet - network not active")
            return false
        }
        
        Log.d(TAG, "Sending ${packet.getTypeDescription()} packet ${packet.messageID}")
        
        // Add to pending transmissions
        pendingTransmissions[packet.messageID] = packet
        
        // Simulate network transmission
        serviceScope.launch {
            simulatePacketTransmission(packet)
        }
        
        return true
    }
    
    /**
     * Get list of connected voice-capable peers
     */
    fun getConnectedPeers(): List<VoicePeer> {
        return connectedPeers.values.filter { it.isReachable() && it.voiceCapable }
    }
    
    /**
     * Get peer by ID
     */
    fun getPeer(peerID: String): VoicePeer? {
        return connectedPeers[peerID]
    }
    
    /**
     * Check if network is active
     */
    fun isNetworkActive(): Boolean = isNetworkActive
    
    /**
     * Get network statistics
     */
    fun getNetworkStatistics(): NetworkStatistics {
        return NetworkStatistics(
            isActive = isNetworkActive,
            connectedPeers = connectedPeers.size,
            voiceCapablePeers = connectedPeers.values.count { it.voiceCapable },
            pendingTransmissions = pendingTransmissions.size,
            averageConnectionQuality = connectedPeers.values.mapNotNull { peer ->
                peer.connectionQuality.value
            }.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f
        )
    }
    
    /**
     * Start mock peer discovery for development
     */
    private fun startMockPeerDiscovery() {
        discoveryJob = serviceScope.launch {
            while (isNetworkActive) {
                try {
                    // Simulate discovering new peers
                    if (connectedPeers.size < MAX_CONCURRENT_PEERS && Random.nextFloat() > 0.5f) {
                        val availablePeers = mockPeers.filter { name ->
                            !connectedPeers.values.any { it.nickname == name }
                        }
                        
                        if (availablePeers.isNotEmpty()) {
                            val peerName = availablePeers.random()
                            val peer = createMockPeer(peerName)
                            
                            connectedPeers[peer.peerID] = peer
                            onPeerDiscovered?.invoke(peer)
                            
                            Log.i(TAG, "Discovered mock peer: $peerName (${peer.peerID})")
                        }
                    }
                    
                    // Simulate peers leaving
                    if (connectedPeers.isNotEmpty() && Random.nextFloat() > 0.8f) {
                        val peerToRemove = connectedPeers.values.random()
                        connectedPeers.remove(peerToRemove.peerID)
                        onPeerLost?.invoke(peerToRemove.peerID)
                        
                        Log.i(TAG, "Lost mock peer: ${peerToRemove.nickname} (${peerToRemove.peerID})")
                    }
                    
                    updateNotification("VoiceMesh Active - ${connectedPeers.size} peers")
                    
                    delay(MOCK_PEER_DISCOVERY_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in peer discovery", e)
                }
            }
        }
    }
    
    /**
     * Start network maintenance tasks
     */
    private fun startNetworkMaintenance() {
        maintenanceJob = serviceScope.launch {
            while (isNetworkActive) {
                try {
                    // Update peer connection quality
                    for (peer in connectedPeers.values.toList()) {
                        val updatedPeer = peer.updateConnection(
                            rssi = Random.nextInt(-90, -30),
                            batteryLevel = Random.nextFloat(),
                            isOnline = Random.nextFloat() > 0.1f // 90% online rate
                        )
                        connectedPeers[peer.peerID] = updatedPeer
                    }
                    
                    // Simulate receiving random voice packets
                    if (connectedPeers.isNotEmpty() && Random.nextFloat() > 0.7f) {
                        simulateIncomingPacket()
                    }
                    
                    delay(5000) // Update every 5 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Error in network maintenance", e)
                }
            }
        }
    }
    
    /**
     * Simulate packet transmission over mesh network
     */
    private suspend fun simulatePacketTransmission(packet: VoicePacket) {
        try {
            // Simulate network delay
            val delay = Random.nextLong(MOCK_MESSAGE_DELAY_MIN, MOCK_MESSAGE_DELAY_MAX)
            delay(delay)
            
            // Simulate delivery success/failure
            val deliverySuccess = Random.nextFloat() < MOCK_DELIVERY_SUCCESS_RATE
            
            if (deliverySuccess) {
                Log.d(TAG, "Packet ${packet.messageID} delivered successfully")
                
                // Simulate acknowledgment for certain packet types
                if (packet.type == VoiceMessageType.VOICE_MESSAGE_FRAGMENT) {
                    delay(Random.nextLong(100, 500))
                    val ackPacket = VoicePacket.createAckPacket(
                        messageID = packet.messageID,
                        senderID = packet.recipientID,
                        recipientID = packet.senderID
                    )
                    onPacketReceived?.invoke(ackPacket)
                }
            } else {
                Log.w(TAG, "Packet ${packet.messageID} delivery failed")
            }
            
            // Remove from pending transmissions
            pendingTransmissions.remove(packet.messageID)
            
            // Notify completion
            onTransmissionComplete?.invoke(packet.messageID, deliverySuccess)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error simulating packet transmission", e)
            pendingTransmissions.remove(packet.messageID)
            onTransmissionComplete?.invoke(packet.messageID, false)
        }
    }
    
    /**
     * Simulate incoming voice packet
     */
    private fun simulateIncomingPacket() {
        try {
            val senderPeer = connectedPeers.values.randomOrNull() ?: return
            
            // Create mock voice packet
            val packetType = VoiceMessageType.values().random()
            val messageID = "msg_${System.currentTimeMillis()}_${Random.nextInt(1000)}"
            
            val packet = when (packetType) {
                VoiceMessageType.VOICE_MESSAGE_START -> {
                    VoicePacket.createStartPacket(
                        messageID = messageID,
                        senderID = senderPeer.peerID.toByteArray(),
                        recipientID = "self".toByteArray(),
                        totalFragments = Random.nextInt(1, 10),
                        compressionType = 0x01u,
                        expirationTime = (System.currentTimeMillis() + 300000).toULong()
                    )
                }
                VoiceMessageType.VOICE_MESSAGE_FRAGMENT -> {
                    VoicePacket.createFragmentPacket(
                        messageID = messageID,
                        senderID = senderPeer.peerID.toByteArray(),
                        recipientID = "self".toByteArray(),
                        fragmentIndex = Random.nextInt(0, 5),
                        totalFragments = Random.nextInt(5, 10),
                        fragmentData = ByteArray(Random.nextInt(100, 400)) { Random.nextInt(256).toByte() },
                        audioChecksum = ByteArray(8) { Random.nextInt(256).toByte() }
                    )
                }
                else -> return // Don't simulate other types for now
            }
            
            Log.d(TAG, "Simulating incoming ${packet.getTypeDescription()} from ${senderPeer.nickname}")
            onPacketReceived?.invoke(packet)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error simulating incoming packet", e)
        }
    }
    
    /**
     * Create mock peer for development
     */
    private fun createMockPeer(name: String): VoicePeer {
        val peerID = "peer_${name.lowercase()}_${Random.nextInt(1000)}"
        val rssi = Random.nextInt(-90, -30)
        
        return VoicePeer.fromBasicPeer(
            peerID = peerID,
            nickname = name,
            fingerprint = "mock_fingerprint_$name",
            rssi = rssi
        ).updateVoiceCapabilities(
            voiceSupported = true,
            maxVoiceSize = 250_000,
            supportedCompressionTypes = setOf(
                com.voicemesh.android.model.CompressionType.AAC_LC
            )
        )
    }
    
    /**
     * Create notification channel for foreground service
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VoiceMesh network service"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Create notification for foreground service
     */
    private fun createNotification(
        title: String = "VoiceMesh",
        content: String = "Voice mesh networking active"
    ): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
    
    /**
     * Update notification content
     */
    private fun updateNotification(content: String) {
        try {
            val notification = createNotification(content = content)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }
}

/**
 * Network statistics data class
 */
data class NetworkStatistics(
    val isActive: Boolean,
    val connectedPeers: Int,
    val voiceCapablePeers: Int,
    val pendingTransmissions: Int,
    val averageConnectionQuality: Float
) {
    
    /**
     * Get formatted statistics summary
     */
    fun getSummary(): String {
        return buildString {
            if (isActive) {
                append("Active: $connectedPeers peers")
                if (voiceCapablePeers != connectedPeers) {
                    append(" ($voiceCapablePeers voice)")
                }
                if (pendingTransmissions > 0) {
                    append(", $pendingTransmissions pending")
                }
                if (averageConnectionQuality > 0) {
                    append(", Quality: ${(averageConnectionQuality * 25).toInt()}%")
                }
            } else {
                append("Network Inactive")
            }
        }
    }
}

/**
 * Voice mesh delegate interface for network callbacks
 */
interface VoiceMeshDelegate {
    fun onPeerDiscovered(peer: VoicePeer)
    fun onPeerLost(peerID: String)
    fun onVoicePacketReceived(packet: VoicePacket)
    fun onVoiceMessageDelivered(messageID: String)
    fun onVoiceMessageFailed(messageID: String, error: String)
    fun onNetworkStateChanged(isActive: Boolean)
} 