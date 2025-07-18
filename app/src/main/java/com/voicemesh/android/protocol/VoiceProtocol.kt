package com.voicemesh.android.protocol

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.voicemesh.android.model.CompressionType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Voice protocol extensions to BitChat protocol
 * Based on MVP specification for voice message types 0x30-0x35
 */

/**
 * Voice message types extending BitChat's MessageType enum
 */
enum class VoiceMessageType(val value: UByte) {
    VOICE_MESSAGE_START(0x30u),     // Start of voice message transmission
    VOICE_MESSAGE_FRAGMENT(0x31u),  // Voice message fragment
    VOICE_MESSAGE_END(0x32u),       // End of voice message transmission
    VOICE_MESSAGE_ACK(0x33u),       // Voice message acknowledgment
    VOICE_MESSAGE_DELETE(0x34u),    // Voice message deletion command
    VOICE_MESSAGE_DELIVERED(0x35u); // Voice message delivered notification
    
    companion object {
        fun fromValue(value: UByte): VoiceMessageType? {
            return values().find { it.value == value }
        }
        
        fun isVoiceMessage(value: UByte): Boolean {
            return value in 0x30u..0x35u
        }
    }
}

/**
 * Voice packet structure for BLE mesh transmission
 * Extends BitChat's packet format with voice-specific data
 */
@Parcelize
data class VoicePacket(
    val type: VoiceMessageType,
    val messageID: String,
    val senderID: ByteArray,
    val recipientID: ByteArray,
    val fragmentData: ByteArray,
    val timestamp: ULong,
    val ttl: UByte,
    val signature: ByteArray? = null,
    
    // Voice-specific fields
    val fragmentIndex: Int = 0,
    val totalFragments: Int = 1,
    val compressionType: UByte = 0x01u, // AAC-LC default
    val audioChecksum: ByteArray = byteArrayOf(),
    val expirationTime: ULong = 0u,
    val deliveryConfirmation: Boolean = false
) : Parcelable {
    
    companion object {
        private const val VOICE_PACKET_VERSION: UByte = 1u
        
        /**
         * Create voice message start packet
         */
        fun createStartPacket(
            messageID: String,
            senderID: ByteArray,
            recipientID: ByteArray,
            totalFragments: Int,
            compressionType: UByte,
            expirationTime: ULong,
            ttl: UByte = 7u
        ): VoicePacket {
            return VoicePacket(
                type = VoiceMessageType.VOICE_MESSAGE_START,
                messageID = messageID,
                senderID = senderID,
                recipientID = recipientID,
                fragmentData = byteArrayOf(),
                timestamp = System.currentTimeMillis().toULong(),
                ttl = ttl,
                totalFragments = totalFragments,
                compressionType = compressionType,
                expirationTime = expirationTime
            )
        }
        
        /**
         * Create voice message fragment packet
         */
        fun createFragmentPacket(
            messageID: String,
            senderID: ByteArray,
            recipientID: ByteArray,
            fragmentIndex: Int,
            totalFragments: Int,
            fragmentData: ByteArray,
            audioChecksum: ByteArray,
            ttl: UByte = 7u
        ): VoicePacket {
            return VoicePacket(
                type = VoiceMessageType.VOICE_MESSAGE_FRAGMENT,
                messageID = messageID,
                senderID = senderID,
                recipientID = recipientID,
                fragmentData = fragmentData,
                timestamp = System.currentTimeMillis().toULong(),
                ttl = ttl,
                fragmentIndex = fragmentIndex,
                totalFragments = totalFragments,
                audioChecksum = audioChecksum
            )
        }
        
        /**
         * Create voice message end packet
         */
        fun createEndPacket(
            messageID: String,
            senderID: ByteArray,
            recipientID: ByteArray,
            totalFragments: Int,
            ttl: UByte = 7u
        ): VoicePacket {
            return VoicePacket(
                type = VoiceMessageType.VOICE_MESSAGE_END,
                messageID = messageID,
                senderID = senderID,
                recipientID = recipientID,
                fragmentData = byteArrayOf(),
                timestamp = System.currentTimeMillis().toULong(),
                ttl = ttl,
                totalFragments = totalFragments
            )
        }
        
        /**
         * Create voice message acknowledgment packet
         */
        fun createAckPacket(
            messageID: String,
            senderID: ByteArray,
            recipientID: ByteArray,
            ttl: UByte = 7u
        ): VoicePacket {
            return VoicePacket(
                type = VoiceMessageType.VOICE_MESSAGE_ACK,
                messageID = messageID,
                senderID = senderID,
                recipientID = recipientID,
                fragmentData = byteArrayOf(),
                timestamp = System.currentTimeMillis().toULong(),
                ttl = ttl
            )
        }
        
        /**
         * Create voice message delete packet
         */
        fun createDeletePacket(
            messageID: String,
            senderID: ByteArray,
            recipientID: ByteArray,
            ttl: UByte = 7u
        ): VoicePacket {
            return VoicePacket(
                type = VoiceMessageType.VOICE_MESSAGE_DELETE,
                messageID = messageID,
                senderID = senderID,
                recipientID = recipientID,
                fragmentData = byteArrayOf(),
                timestamp = System.currentTimeMillis().toULong(),
                ttl = ttl
            )
        }
        
        /**
         * Create voice message delivered packet
         */
        fun createDeliveredPacket(
            messageID: String,
            senderID: ByteArray,
            recipientID: ByteArray,
            ttl: UByte = 7u
        ): VoicePacket {
            return VoicePacket(
                type = VoiceMessageType.VOICE_MESSAGE_DELIVERED,
                messageID = messageID,
                senderID = senderID,
                recipientID = recipientID,
                fragmentData = byteArrayOf(),
                timestamp = System.currentTimeMillis().toULong(),
                ttl = ttl,
                deliveryConfirmation = true
            )
        }
        
        /**
         * Deserialize voice packet from binary data
         */
        fun fromBinary(data: ByteArray): VoicePacket? {
            try {
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                
                // Read header
                val version = buffer.get().toUByte()
                if (version != VOICE_PACKET_VERSION) return null
                
                val typeValue = buffer.get().toUByte()
                val type = VoiceMessageType.fromValue(typeValue) ?: return null
                
                val messageIDLength = buffer.get().toInt()
                val messageID = String(ByteArray(messageIDLength).also { buffer.get(it) })
                
                val senderIDLength = buffer.get().toInt()
                val senderID = ByteArray(senderIDLength).also { buffer.get(it) }
                
                val recipientIDLength = buffer.get().toInt()
                val recipientID = ByteArray(recipientIDLength).also { buffer.get(it) }
                
                val timestamp = buffer.long.toULong()
                val ttl = buffer.get().toUByte()
                
                // Voice-specific fields
                val fragmentIndex = buffer.int
                val totalFragments = buffer.int
                val compressionType = buffer.get().toUByte()
                val expirationTime = buffer.long.toULong()
                val deliveryConfirmation = buffer.get() == 1.toByte()
                
                val checksumLength = buffer.get().toInt()
                val audioChecksum = ByteArray(checksumLength).also { buffer.get(it) }
                
                val fragmentDataLength = buffer.int
                val fragmentData = ByteArray(fragmentDataLength).also { buffer.get(it) }
                
                val signatureLength = buffer.get().toInt()
                val signature = if (signatureLength > 0) {
                    ByteArray(signatureLength).also { buffer.get(it) }
                } else null
                
                return VoicePacket(
                    type = type,
                    messageID = messageID,
                    senderID = senderID,
                    recipientID = recipientID,
                    fragmentData = fragmentData,
                    timestamp = timestamp,
                    ttl = ttl,
                    signature = signature,
                    fragmentIndex = fragmentIndex,
                    totalFragments = totalFragments,
                    compressionType = compressionType,
                    audioChecksum = audioChecksum,
                    expirationTime = expirationTime,
                    deliveryConfirmation = deliveryConfirmation
                )
                
            } catch (e: Exception) {
                return null
            }
        }
    }
    
    /**
     * Serialize voice packet to binary format
     */
    fun toBinary(): ByteArray {
        val messageIDBytes = messageID.toByteArray()
        val signatureBytes = signature ?: byteArrayOf()
        
        val buffer = ByteBuffer.allocate(
            1 + // version
            1 + // type
            1 + messageIDBytes.size + // messageID
            1 + senderID.size + // senderID
            1 + recipientID.size + // recipientID
            8 + // timestamp
            1 + // ttl
            4 + // fragmentIndex
            4 + // totalFragments
            1 + // compressionType
            8 + // expirationTime
            1 + // deliveryConfirmation
            1 + audioChecksum.size + // audioChecksum
            4 + fragmentData.size + // fragmentData
            1 + signatureBytes.size // signature
        ).order(ByteOrder.BIG_ENDIAN)
        
        buffer.put(VOICE_PACKET_VERSION.toByte())
        buffer.put(type.value.toByte())
        
        buffer.put(messageIDBytes.size.toByte())
        buffer.put(messageIDBytes)
        
        buffer.put(senderID.size.toByte())
        buffer.put(senderID)
        
        buffer.put(recipientID.size.toByte())
        buffer.put(recipientID)
        
        buffer.putLong(timestamp.toLong())
        buffer.put(ttl.toInt().toByte())
        
        buffer.putInt(fragmentIndex)
        buffer.putInt(totalFragments)
        buffer.put(1.toByte()) // CompressionType placeholder
        buffer.putLong(expirationTime.toLong())
        buffer.put(if (deliveryConfirmation) 1 else 0)
        
        buffer.put(audioChecksum.size.toByte())
        buffer.put(audioChecksum)
        
        buffer.putInt(fragmentData.size)
        buffer.put(fragmentData)
        
        buffer.put(signatureBytes.size.toByte())
        buffer.put(signatureBytes)
        
        return buffer.array()
    }
    
    /**
     * Check if packet is expired based on TTL and timestamp
     */
    fun isExpired(): Boolean {
        val ageMs = System.currentTimeMillis().toULong() - timestamp
        return ageMs > (5u * 60u * 1000u) // 5 minutes max age
    }
    
    /**
     * Decrement TTL for forwarding
     */
    fun decrementTTL(): VoicePacket {
        return copy(ttl = if (ttl > 0u) (ttl - 1u).toUByte() else 0u)
    }
    
    /**
     * Check if packet should be forwarded (TTL > 0)
     */
    fun shouldForward(): Boolean {
        return ttl > 0u && !isExpired()
    }
    
    /**
     * Get packet size in bytes
     */
    fun getPacketSize(): Int {
        return toBinary().size
    }
    
    /**
     * Get voice message type description
     */
    fun getTypeDescription(): String {
        return when (type) {
            VoiceMessageType.VOICE_MESSAGE_START -> "Voice Start"
            VoiceMessageType.VOICE_MESSAGE_FRAGMENT -> "Voice Fragment ($fragmentIndex/$totalFragments)"
            VoiceMessageType.VOICE_MESSAGE_END -> "Voice End"
            VoiceMessageType.VOICE_MESSAGE_ACK -> "Voice Ack"
            VoiceMessageType.VOICE_MESSAGE_DELETE -> "Voice Delete"
            VoiceMessageType.VOICE_MESSAGE_DELIVERED -> "Voice Delivered"
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as VoicePacket
        
        return type == other.type &&
                messageID == other.messageID &&
                senderID.contentEquals(other.senderID) &&
                recipientID.contentEquals(other.recipientID) &&
                fragmentData.contentEquals(other.fragmentData) &&
                timestamp == other.timestamp &&
                ttl == other.ttl &&
                (signature?.contentEquals(other.signature) ?: (other.signature == null)) &&
                fragmentIndex == other.fragmentIndex &&
                totalFragments == other.totalFragments &&
                compressionType == other.compressionType &&
                audioChecksum.contentEquals(other.audioChecksum) &&
                expirationTime == other.expirationTime &&
                deliveryConfirmation == other.deliveryConfirmation
    }
    
    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + messageID.hashCode()
        result = 31 * result + senderID.contentHashCode()
        result = 31 * result + recipientID.contentHashCode()
        result = 31 * result + fragmentData.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + ttl.hashCode()
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        result = 31 * result + fragmentIndex
        result = 31 * result + totalFragments
        result = 31 * result + compressionType.hashCode()
        result = 31 * result + audioChecksum.contentHashCode()
        result = 31 * result + expirationTime.hashCode()
        result = 31 * result + deliveryConfirmation.hashCode()
        return result
    }
}

/**
 * Voice protocol handler result
 */
sealed class VoiceProtocolResult {
    object Success : VoiceProtocolResult()
    object FragmentAdded : VoiceProtocolResult()
    object MessageComplete : VoiceProtocolResult()
    object MessageDeleted : VoiceProtocolResult()
    object Acknowledged : VoiceProtocolResult()
    data class Error(val message: String) : VoiceProtocolResult()
    data class InvalidPacket(val reason: String) : VoiceProtocolResult()
    object Expired : VoiceProtocolResult()
    object TTLExceeded : VoiceProtocolResult()
} 