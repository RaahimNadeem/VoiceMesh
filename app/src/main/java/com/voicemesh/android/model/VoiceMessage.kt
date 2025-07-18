package com.voicemesh.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * Ephemeral voice message model - implements the MVP specification
 * Self-destructing voice messages that delete after delivery or 5 minutes
 */
@Parcelize
data class EphemeralVoiceMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderID: String,
    val recipientID: String,
    val audioData: ByteArray,
    val timestamp: Date = Date(),
    val expirationTime: Date = Date(System.currentTimeMillis() + 300_000), // 5 minutes
    val deliveryConfirmation: Boolean = false,
    val duration: Float = 0f, // Duration in seconds
    val isDelivered: Boolean = false,
    val isPlayed: Boolean = false,
    val compressionFormat: String = "AAC-LC"
) : Parcelable {

    /**
     * Convert voice message to binary payload for transmission
     */
    fun toBinaryPayload(): ByteArray? {
        try {
            val buffer = ByteBuffer.allocate(8192).apply { order(ByteOrder.BIG_ENDIAN) }
            
            // Voice message format:
            // - Version: 1 byte (0x01)
            // - Type: 1 byte (voice message)
            // - Timestamp: 8 bytes (milliseconds since epoch)
            // - Expiration: 8 bytes (milliseconds since epoch)
            // - Duration: 4 bytes (float, seconds)
            // - Flags: 1 byte (delivered, played, etc.)
            // - ID length: 1 byte + ID data
            // - Sender ID length: 1 byte + sender ID data
            // - Recipient ID length: 1 byte + recipient ID data
            // - Audio data length: 4 bytes + audio data
            
            buffer.put(0x01) // Version
            buffer.put(0x30) // Voice message type (from MVP spec)
            
            // Timestamps
            buffer.putLong(timestamp.time)
            buffer.putLong(expirationTime.time)
            
            // Duration
            buffer.putFloat(duration)
            
            // Flags
            var flags: UByte = 0u
            if (deliveryConfirmation) flags = flags or 0x01u
            if (isDelivered) flags = flags or 0x02u
            if (isPlayed) flags = flags or 0x04u
            buffer.put(flags.toByte())
            
            // ID
            val idBytes = id.toByteArray(Charsets.UTF_8)
            buffer.put(minOf(idBytes.size, 255).toByte())
            buffer.put(idBytes.take(255).toByteArray())
            
            // Sender ID
            val senderBytes = senderID.toByteArray(Charsets.UTF_8)
            buffer.put(minOf(senderBytes.size, 255).toByte())
            buffer.put(senderBytes.take(255).toByteArray())
            
            // Recipient ID
            val recipientBytes = recipientID.toByteArray(Charsets.UTF_8)
            buffer.put(minOf(recipientBytes.size, 255).toByte())
            buffer.put(recipientBytes.take(255).toByteArray())
            
            // Audio data
            val audioLength = minOf(audioData.size, 1_000_000) // 1MB max
            buffer.putInt(audioLength)
            buffer.put(audioData.take(audioLength).toByteArray())
            
            return buffer.array().take(buffer.position()).toByteArray()
            
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Check if message has expired
     */
    fun isExpired(): Boolean {
        return Date().after(expirationTime)
    }

    /**
     * Get remaining time until expiration in milliseconds
     */
    fun getRemainingTime(): Long {
        return maxOf(0, expirationTime.time - System.currentTimeMillis())
    }

    /**
     * Get human-readable expiration time
     */
    fun getExpirationTimeString(): String {
        val remaining = getRemainingTime()
        return when {
            remaining <= 0 -> "Expired"
            remaining < 60_000 -> "${remaining / 1000}s"
            else -> "${remaining / 60_000}m ${(remaining % 60_000) / 1000}s"
        }
    }

    companion object {
        /**
         * Parse voice message from binary data
         */
        fun fromBinaryPayload(data: ByteArray): EphemeralVoiceMessage? {
            try {
                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }
                
                val version = buffer.get()
                if (version != 0x01.toByte()) return null
                
                val type = buffer.get()
                if (type != 0x30.toByte()) return null // Voice message type
                
                val timestamp = Date(buffer.long)
                val expirationTime = Date(buffer.long)
                val duration = buffer.float
                val flags = buffer.get()
                
                // Parse flags
                val deliveryConfirmation = (flags.toInt() and 0x01) != 0
                val isDelivered = (flags.toInt() and 0x02) != 0
                val isPlayed = (flags.toInt() and 0x04) != 0
                
                // Parse ID
                val idLength = buffer.get().toInt() and 0xFF
                val idBytes = ByteArray(idLength)
                buffer.get(idBytes)
                val id = String(idBytes, Charsets.UTF_8)
                
                // Parse sender ID
                val senderLength = buffer.get().toInt() and 0xFF
                val senderBytes = ByteArray(senderLength)
                buffer.get(senderBytes)
                val senderID = String(senderBytes, Charsets.UTF_8)
                
                // Parse recipient ID
                val recipientLength = buffer.get().toInt() and 0xFF
                val recipientBytes = ByteArray(recipientLength)
                buffer.get(recipientBytes)
                val recipientID = String(recipientBytes, Charsets.UTF_8)
                
                // Parse audio data
                val audioLength = buffer.int
                val audioData = ByteArray(audioLength)
                buffer.get(audioData)
                
                return EphemeralVoiceMessage(
                    id = id,
                    senderID = senderID,
                    recipientID = recipientID,
                    audioData = audioData,
                    timestamp = timestamp,
                    expirationTime = expirationTime,
                    deliveryConfirmation = deliveryConfirmation,
                    duration = duration,
                    isDelivered = isDelivered,
                    isPlayed = isPlayed
                )
                
            } catch (e: Exception) {
                return null
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EphemeralVoiceMessage

        if (id != other.id) return false
        if (senderID != other.senderID) return false
        if (recipientID != other.recipientID) return false
        if (!audioData.contentEquals(other.audioData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + senderID.hashCode()
        result = 31 * result + recipientID.hashCode()
        result = 31 * result + audioData.contentHashCode()
        return result
    }
}

/**
 * Voice message fragment for BLE transmission
 * Implements fragmentation strategy from MVP spec (450 bytes per fragment)
 */
@Parcelize
data class VoiceFragment(
    val messageID: String,
    val fragmentIndex: Int,
    val totalFragments: Int,
    val audioData: ByteArray,
    val checksum: ByteArray // SHA-256 checksum for error detection
) : Parcelable {

    companion object {
        const val FRAGMENT_SIZE = 450 // Safe for BLE MTU limits
        
        /**
         * Create fragments from voice message
         */
        fun createFragments(message: EphemeralVoiceMessage): List<VoiceFragment> {
            val audioData = message.audioData
            val totalFragments = (audioData.size + FRAGMENT_SIZE - 1) / FRAGMENT_SIZE
            val fragments = mutableListOf<VoiceFragment>()
            
            for (i in 0 until totalFragments) {
                val start = i * FRAGMENT_SIZE
                val end = minOf(start + FRAGMENT_SIZE, audioData.size)
                val fragmentData = audioData.copyOfRange(start, end)
                
                // Calculate SHA-256 checksum
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val checksum = digest.digest(fragmentData)
                
                fragments.add(
                    VoiceFragment(
                        messageID = message.id,
                        fragmentIndex = i,
                        totalFragments = totalFragments,
                        audioData = fragmentData,
                        checksum = checksum
                    )
                )
            }
            
            return fragments
        }
        
        /**
         * Reassemble fragments into complete audio data
         */
        fun reassembleFragments(fragments: List<VoiceFragment>): ByteArray? {
            if (fragments.isEmpty()) return null
            
            // Verify all fragments belong to same message
            val messageID = fragments.first().messageID
            if (!fragments.all { it.messageID == messageID }) return null
            
            // Sort by fragment index
            val sortedFragments = fragments.sortedBy { it.fragmentIndex }
            
            // Verify we have all fragments
            val totalFragments = fragments.first().totalFragments
            if (sortedFragments.size != totalFragments) return null
            if (!sortedFragments.indices.all { i -> sortedFragments[i].fragmentIndex == i }) return null
            
            // Verify checksums and reassemble
            val result = ByteArray(sortedFragments.sumOf { it.audioData.size })
            var offset = 0
            
            for (fragment in sortedFragments) {
                // Verify checksum
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val expectedChecksum = digest.digest(fragment.audioData)
                if (!fragment.checksum.contentEquals(expectedChecksum)) {
                    return null // Corruption detected
                }
                
                // Copy data
                System.arraycopy(fragment.audioData, 0, result, offset, fragment.audioData.size)
                offset += fragment.audioData.size
            }
            
            return result
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VoiceFragment

        if (messageID != other.messageID) return false
        if (fragmentIndex != other.fragmentIndex) return false
        if (!audioData.contentEquals(other.audioData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = messageID.hashCode()
        result = 31 * result + fragmentIndex
        result = 31 * result + audioData.contentHashCode()
        return result
    }
}

/**
 * Voice peer information - extends basic peer data with voice capabilities
 */
@Parcelize
data class VoicePeer(
    val peerID: String,
    val nickname: String,
    val fingerprint: String,
    val voiceCapable: Boolean = true,
    val lastSeen: Date = Date(),
    val batteryLevel: Float? = null,
    val connectionStrength: Int = 0, // RSSI or connection quality indicator
    val isOnline: Boolean = true
) : Parcelable {

    /**
     * Get connection quality description
     */
    fun getConnectionQuality(): String {
        return when {
            connectionStrength >= -50 -> "Excellent"
            connectionStrength >= -70 -> "Good"
            connectionStrength >= -85 -> "Fair"
            else -> "Poor"
        }
    }

    /**
     * Check if peer was recently seen (within last 5 minutes)
     */
    fun isRecentlySeen(): Boolean {
        return System.currentTimeMillis() - lastSeen.time < 300_000
    }
} 