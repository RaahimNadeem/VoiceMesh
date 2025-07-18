package com.voicemesh.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.*

/**
 * Ephemeral voice message model - self-destructing voice messages for VoiceMesh
 * Based on MVP specification for 5-minute expiration and instant deletion after playback
 */
@Parcelize
data class EphemeralVoiceMessage(
    val id: String,
    val senderID: String,
    val recipientID: String,
    val audioData: ByteArray,
    val timestamp: Long,
    val expirationTime: Long,
    val deliveryConfirmation: Boolean = false,
    val isPlayed: Boolean = false,
    val compressionType: CompressionType = CompressionType.AAC_LC,
    val originalSize: Int = audioData.size,
    val checksum: ByteArray = calculateChecksum(audioData)
) : Parcelable {

    companion object {
        private const val EXPIRATION_DURATION_MS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_AUDIO_SIZE = 250_000 // ~240KB as per MVP spec
        
        /**
         * Create a new ephemeral voice message
         */
        fun create(
            senderID: String,
            recipientID: String,
            audioData: ByteArray,
            compressionType: CompressionType = CompressionType.AAC_LC
        ): EphemeralVoiceMessage? {
            if (audioData.size > MAX_AUDIO_SIZE) {
                return null // Audio too large
            }
            
            val now = System.currentTimeMillis()
            return EphemeralVoiceMessage(
                id = generateMessageID(),
                senderID = senderID,
                recipientID = recipientID,
                audioData = audioData,
                timestamp = now,
                expirationTime = now + EXPIRATION_DURATION_MS,
                compressionType = compressionType
            )
        }
        
        /**
         * Generate a unique message ID
         */
        private fun generateMessageID(): String {
            return UUID.randomUUID().toString().replace("-", "").take(16)
        }
        
        /**
         * Calculate SHA-256 checksum for audio data integrity
         */
        private fun calculateChecksum(data: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data).take(8).toByteArray() // First 8 bytes for efficiency
        }
        
        /**
         * Deserialize from binary format
         */
        fun fromBinary(data: ByteArray): EphemeralVoiceMessage? {
            try {
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                
                // Read header
                val version = buffer.get().toUByte()
                if (version != 1u.toByte()) return null
                
                val idLength = buffer.get().toInt()
                val id = String(ByteArray(idLength).also { buffer.get(it) })
                
                val senderIDLength = buffer.get().toInt()
                val senderID = String(ByteArray(senderIDLength).also { buffer.get(it) })
                
                val recipientIDLength = buffer.get().toInt()
                val recipientID = String(ByteArray(recipientIDLength).also { buffer.get(it) })
                
                val timestamp = buffer.long
                val expirationTime = buffer.long
                val deliveryConfirmation = buffer.get() == 1.toByte()
                val isPlayed = buffer.get() == 1.toByte()
                val compressionType = CompressionType.fromValue(buffer.get().toUByte()) ?: CompressionType.AAC_LC
                val originalSize = buffer.int
                
                val checksumLength = buffer.get().toInt()
                val checksum = ByteArray(checksumLength).also { buffer.get(it) }
                
                val audioDataLength = buffer.int
                val audioData = ByteArray(audioDataLength).also { buffer.get(it) }
                
                return EphemeralVoiceMessage(
                    id = id,
                    senderID = senderID,
                    recipientID = recipientID,
                    audioData = audioData,
                    timestamp = timestamp,
                    expirationTime = expirationTime,
                    deliveryConfirmation = deliveryConfirmation,
                    isPlayed = isPlayed,
                    compressionType = compressionType,
                    originalSize = originalSize,
                    checksum = checksum
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
    
    /**
     * Check if message has expired
     */
    fun isExpired(): Boolean {
        return System.currentTimeMillis() > expirationTime
    }
    
    /**
     * Check if message should be deleted (played or expired)
     */
    fun shouldDelete(): Boolean {
        return isPlayed || isExpired()
    }
    
    /**
     * Get remaining time until expiration in milliseconds
     */
    fun remainingTimeMs(): Long {
        return maxOf(0, expirationTime - System.currentTimeMillis())
    }
    
    /**
     * Mark message as played (triggers deletion)
     */
    fun markAsPlayed(): EphemeralVoiceMessage {
        return copy(isPlayed = true)
    }
    
    /**
     * Mark delivery confirmation received
     */
    fun markDelivered(): EphemeralVoiceMessage {
        return copy(deliveryConfirmation = true)
    }
    
    /**
     * Verify audio data integrity
     */
    fun verifyIntegrity(): Boolean {
        return checksum.contentEquals(calculateChecksum(audioData))
    }
    
    /**
     * Get estimated duration based on compression type and size
     */
    fun getEstimatedDurationSeconds(): Int {
        return when (compressionType) {
            CompressionType.AAC_LC -> {
                // AAC-LC at 32kbps = 4KB per second
                audioData.size / 4000
            }
            CompressionType.OPUS -> {
                // Opus at similar bitrate
                audioData.size / 4000
            }
            CompressionType.UNCOMPRESSED -> {
                // PCM 16-bit mono at 16kHz = 32KB per second
                audioData.size / 32000
            }
        }
    }
    
    /**
     * Serialize to binary format for network transmission
     */
    fun toBinary(): ByteArray {
        val idBytes = id.toByteArray()
        val senderIDBytes = senderID.toByteArray()
        val recipientIDBytes = recipientID.toByteArray()
        
        val buffer = ByteBuffer.allocate(
            1 + // version
            1 + idBytes.size + // id
            1 + senderIDBytes.size + // senderID
            1 + recipientIDBytes.size + // recipientID
            8 + // timestamp
            8 + // expirationTime
            1 + // deliveryConfirmation
            1 + // isPlayed
            1 + // compressionType
            4 + // originalSize
            1 + checksum.size + // checksum
            4 + audioData.size // audioData
        ).order(ByteOrder.BIG_ENDIAN)
        
        buffer.put(1) // version
        buffer.put(idBytes.size.toByte())
        buffer.put(idBytes)
        buffer.put(senderIDBytes.size.toByte())
        buffer.put(senderIDBytes)
        buffer.put(recipientIDBytes.size.toByte())
        buffer.put(recipientIDBytes)
        buffer.putLong(timestamp)
        buffer.putLong(expirationTime)
        buffer.put(if (deliveryConfirmation) 1 else 0)
        buffer.put(if (isPlayed) 1 else 0)
        buffer.put(compressionType.value.toByte())
        buffer.putInt(originalSize)
        buffer.put(checksum.size.toByte())
        buffer.put(checksum)
        buffer.putInt(audioData.size)
        buffer.put(audioData)
        
        return buffer.array()
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as EphemeralVoiceMessage
        
        return id == other.id &&
                senderID == other.senderID &&
                recipientID == other.recipientID &&
                audioData.contentEquals(other.audioData) &&
                timestamp == other.timestamp &&
                expirationTime == other.expirationTime &&
                deliveryConfirmation == other.deliveryConfirmation &&
                isPlayed == other.isPlayed &&
                compressionType == other.compressionType &&
                originalSize == other.originalSize &&
                checksum.contentEquals(other.checksum)
    }
    
    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + senderID.hashCode()
        result = 31 * result + recipientID.hashCode()
        result = 31 * result + audioData.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + expirationTime.hashCode()
        result = 31 * result + deliveryConfirmation.hashCode()
        result = 31 * result + isPlayed.hashCode()
        result = 31 * result + compressionType.hashCode()
        result = 31 * result + originalSize
        result = 31 * result + checksum.contentHashCode()
        return result
    }
}

/**
 * Audio compression types supported by VoiceMesh
 */
enum class CompressionType(val value: UByte) {
    AAC_LC(0x01u),      // AAC-LC (default, good compression/quality balance)
    OPUS(0x02u),        // Opus codec (excellent for voice)
    UNCOMPRESSED(0x03u); // Raw PCM (for testing/debugging)
    
    companion object {
        fun fromValue(value: UByte): CompressionType? {
            return values().find { it.value == value }
        }
    }
} 