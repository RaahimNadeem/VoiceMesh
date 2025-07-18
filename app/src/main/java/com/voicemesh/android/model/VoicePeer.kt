package com.voicemesh.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Voice-capable peer in the mesh network
 * Based on MVP specification for peer management with voice capability indicators
 */
@Parcelize
data class VoicePeer(
    val peerID: String,
    val nickname: String,
    val fingerprint: String,
    val voiceCapable: Boolean = true,
    val lastSeen: Long = System.currentTimeMillis(),
    val batteryLevel: Float? = null,
    val connectionQuality: ConnectionQuality = ConnectionQuality.UNKNOWN,
    val rssi: Int? = null,
    val voiceSupported: Boolean = true,
    val maxVoiceSize: Int = 250_000, // 240KB default as per MVP
    val supportedCompressionTypes: Set<CompressionType> = setOf(CompressionType.AAC_LC),
    val isOnline: Boolean = true,
    val pendingVoiceMessages: Int = 0,
    val totalVoiceMessagesExchanged: Int = 0
) : Parcelable {

    companion object {
        private const val PEER_TIMEOUT_MS = 60000L // 1 minute
        private const val STRONG_RSSI_THRESHOLD = -60
        private const val MEDIUM_RSSI_THRESHOLD = -80
        
        /**
         * Create a new voice peer from basic peer information
         */
        fun fromBasicPeer(
            peerID: String,
            nickname: String = "Anonymous",
            fingerprint: String = "",
            rssi: Int? = null
        ): VoicePeer {
            return VoicePeer(
                peerID = peerID,
                nickname = nickname,
                fingerprint = fingerprint,
                connectionQuality = determineConnectionQuality(rssi),
                rssi = rssi
            )
        }
        
        /**
         * Determine connection quality from RSSI
         */
        private fun determineConnectionQuality(rssi: Int?): ConnectionQuality {
            return when {
                rssi == null -> ConnectionQuality.UNKNOWN
                rssi >= STRONG_RSSI_THRESHOLD -> ConnectionQuality.EXCELLENT
                rssi >= MEDIUM_RSSI_THRESHOLD -> ConnectionQuality.GOOD
                else -> ConnectionQuality.WEAK
            }
        }
    }
    
    /**
     * Check if peer is considered online/reachable
     */
    fun isReachable(): Boolean {
        return isOnline && (System.currentTimeMillis() - lastSeen) < PEER_TIMEOUT_MS
    }
    
    /**
     * Check if peer can receive voice messages
     */
    fun canReceiveVoice(): Boolean {
        return voiceCapable && voiceSupported && isReachable()
    }
    
    /**
     * Check if peer supports specific compression type
     */
    fun supportsCompression(compressionType: CompressionType): Boolean {
        return supportedCompressionTypes.contains(compressionType)
    }
    
    /**
     * Get best compression type for this peer
     */
    fun getBestCompressionType(): CompressionType {
        return when {
            supportedCompressionTypes.contains(CompressionType.OPUS) -> CompressionType.OPUS
            supportedCompressionTypes.contains(CompressionType.AAC_LC) -> CompressionType.AAC_LC
            supportedCompressionTypes.contains(CompressionType.UNCOMPRESSED) -> CompressionType.UNCOMPRESSED
            else -> CompressionType.AAC_LC // Default fallback
        }
    }
    
    /**
     * Check if peer can handle voice message of given size
     */
    fun canHandleVoiceSize(sizeBytes: Int): Boolean {
        return sizeBytes <= maxVoiceSize
    }
    
    /**
     * Update peer with new connection information
     */
    fun updateConnection(
        rssi: Int? = null,
        batteryLevel: Float? = null,
        isOnline: Boolean = true
    ): VoicePeer {
        return copy(
            lastSeen = System.currentTimeMillis(),
            rssi = rssi ?: this.rssi,
            batteryLevel = batteryLevel ?: this.batteryLevel,
            connectionQuality = determineConnectionQuality(rssi ?: this.rssi),
            isOnline = isOnline
        )
    }
    
    /**
     * Update voice capabilities
     */
    fun updateVoiceCapabilities(
        voiceSupported: Boolean = this.voiceSupported,
        maxVoiceSize: Int = this.maxVoiceSize,
        supportedCompressionTypes: Set<CompressionType> = this.supportedCompressionTypes
    ): VoicePeer {
        return copy(
            voiceSupported = voiceSupported,
            maxVoiceSize = maxVoiceSize,
            supportedCompressionTypes = supportedCompressionTypes,
            voiceCapable = voiceSupported && supportedCompressionTypes.isNotEmpty()
        )
    }
    
    /**
     * Increment pending voice message count
     */
    fun addPendingVoiceMessage(): VoicePeer {
        return copy(pendingVoiceMessages = pendingVoiceMessages + 1)
    }
    
    /**
     * Decrement pending voice message count and increment total exchanged
     */
    fun markVoiceMessageDelivered(): VoicePeer {
        return copy(
            pendingVoiceMessages = maxOf(0, pendingVoiceMessages - 1),
            totalVoiceMessagesExchanged = totalVoiceMessagesExchanged + 1
        )
    }
    
    /**
     * Get peer status summary
     */
    fun getStatusSummary(): String {
        return buildString {
            append(nickname)
            if (!isReachable()) {
                append(" (offline)")
            } else {
                append(" (")
                append(connectionQuality.displayName)
                if (pendingVoiceMessages > 0) {
                    append(", $pendingVoiceMessages pending")
                }
                append(")")
            }
        }
    }
    
    /**
     * Get connection strength as percentage (0-100)
     */
    fun getConnectionStrengthPercentage(): Int {
        return when (connectionQuality) {
            ConnectionQuality.EXCELLENT -> 90
            ConnectionQuality.GOOD -> 70
            ConnectionQuality.FAIR -> 50
            ConnectionQuality.WEAK -> 30
            ConnectionQuality.UNKNOWN -> 0
        }
    }
    
    /**
     * Check if peer needs capability refresh
     */
    fun needsCapabilityRefresh(refreshIntervalMs: Long = 300000): Boolean {
        return (System.currentTimeMillis() - lastSeen) > refreshIntervalMs
    }
}

/**
 * Connection quality levels for voice communication
 */
enum class ConnectionQuality(val displayName: String, val value: Int) {
    EXCELLENT("Excellent", 4),
    GOOD("Good", 3),
    FAIR("Fair", 2),
    WEAK("Weak", 1),
    UNKNOWN("Unknown", 0);
    
    companion object {
        fun fromValue(value: Int): ConnectionQuality {
            return values().find { it.value == value } ?: UNKNOWN
        }
    }
    
    /**
     * Check if quality is sufficient for voice communication
     */
    fun isSufficientForVoice(): Boolean {
        return this in setOf(EXCELLENT, GOOD, FAIR)
    }
}

/**
 * Voice peer statistics for analytics and optimization
 */
@Parcelize
data class VoicePeerStats(
    val peerID: String,
    val totalMessagesReceived: Int = 0,
    val totalMessagesSent: Int = 0,
    val totalBytesReceived: Long = 0L,
    val totalBytesSent: Long = 0L,
    val averageDeliveryTimeMs: Long = 0L,
    val failedDeliveries: Int = 0,
    val lastMessageTime: Long = 0L,
    val preferredCompressionType: CompressionType = CompressionType.AAC_LC
) : Parcelable {
    
    /**
     * Update stats with new message sent
     */
    fun recordMessageSent(sizeBytes: Int, deliveryTimeMs: Long? = null): VoicePeerStats {
        return copy(
            totalMessagesSent = totalMessagesSent + 1,
            totalBytesSent = totalBytesSent + sizeBytes,
            lastMessageTime = System.currentTimeMillis(),
            averageDeliveryTimeMs = if (deliveryTimeMs != null) {
                (averageDeliveryTimeMs + deliveryTimeMs) / 2
            } else averageDeliveryTimeMs
        )
    }
    
    /**
     * Update stats with new message received
     */
    fun recordMessageReceived(sizeBytes: Int): VoicePeerStats {
        return copy(
            totalMessagesReceived = totalMessagesReceived + 1,
            totalBytesReceived = totalBytesReceived + sizeBytes,
            lastMessageTime = System.currentTimeMillis()
        )
    }
    
    /**
     * Record failed delivery
     */
    fun recordFailedDelivery(): VoicePeerStats {
        return copy(failedDeliveries = failedDeliveries + 1)
    }
    
    /**
     * Get delivery success rate (0.0 to 1.0)
     */
    fun getSuccessRate(): Float {
        val totalAttempts = totalMessagesSent + failedDeliveries
        return if (totalAttempts > 0) {
            totalMessagesSent.toFloat() / totalAttempts.toFloat()
        } else 1.0f
    }
    
    /**
     * Check if peer is active in voice communication
     */
    fun isActive(activeThresholdMs: Long = 300000): Boolean {
        return (System.currentTimeMillis() - lastMessageTime) < activeThresholdMs
    }
} 