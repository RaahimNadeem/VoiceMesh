package com.voicemesh.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Voice fragment for BLE mesh transmission - handles fragmentation of voice messages
 * Based on MVP specification for 450-byte fragments with SHA-256 checksums
 */
@Parcelize
data class VoiceFragment(
    val messageID: String,
    val fragmentIndex: Int,
    val totalFragments: Int,
    val audioData: ByteArray,
    val checksum: ByteArray = calculateFragmentChecksum(audioData),
    val timestamp: Long = System.currentTimeMillis()
) : Parcelable {

    companion object {
        private const val MAX_FRAGMENT_SIZE = 450 // Bytes, safe for BLE MTU
        private const val HEADER_SIZE = 64 // Estimated header overhead
        private const val MAX_AUDIO_DATA_SIZE = MAX_FRAGMENT_SIZE - HEADER_SIZE // ~386 bytes
        
        /**
         * Fragment a voice message into BLE-sized chunks
         */
        fun fragmentVoiceMessage(message: EphemeralVoiceMessage): List<VoiceFragment> {
            val audioData = message.audioData
            val fragments = mutableListOf<VoiceFragment>()
            
            // Calculate number of fragments needed
            val totalFragments = (audioData.size + MAX_AUDIO_DATA_SIZE - 1) / MAX_AUDIO_DATA_SIZE
            
            for (i in 0 until totalFragments) {
                val startIndex = i * MAX_AUDIO_DATA_SIZE
                val endIndex = minOf(startIndex + MAX_AUDIO_DATA_SIZE, audioData.size)
                val fragmentData = audioData.sliceArray(startIndex until endIndex)
                
                fragments.add(
                    VoiceFragment(
                        messageID = message.id,
                        fragmentIndex = i,
                        totalFragments = totalFragments,
                        audioData = fragmentData
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
            
            // Validate fragments
            val messageID = fragments.first().messageID
            val totalFragments = fragments.first().totalFragments
            
            if (fragments.size != totalFragments) return null
            if (fragments.any { it.messageID != messageID || it.totalFragments != totalFragments }) return null
            
            // Sort fragments by index
            val sortedFragments = fragments.sortedBy { it.fragmentIndex }
            
            // Verify all fragments are present and valid
            for (i in sortedFragments.indices) {
                val fragment = sortedFragments[i]
                if (fragment.fragmentIndex != i) return null
                if (!fragment.verifyChecksum()) return null
            }
            
            // Reassemble audio data
            val totalSize = sortedFragments.sumOf { it.audioData.size }
            val reassembledData = ByteArray(totalSize)
            var offset = 0
            
            for (fragment in sortedFragments) {
                fragment.audioData.copyInto(reassembledData, offset)
                offset += fragment.audioData.size
            }
            
            return reassembledData
        }
        
        /**
         * Calculate SHA-256 checksum for fragment data
         */
        private fun calculateFragmentChecksum(data: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data).take(8).toByteArray() // First 8 bytes for efficiency
        }
        
        /**
         * Deserialize fragment from binary format
         */
        fun fromBinary(data: ByteArray): VoiceFragment? {
            try {
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                
                // Read header
                val version = buffer.get().toUByte()
                if (version != 1u.toUByte()) return null
                
                val messageIDLength = buffer.get().toInt()
                val messageID = String(ByteArray(messageIDLength).also { buffer.get(it) })
                
                val fragmentIndex = buffer.int
                val totalFragments = buffer.int
                val timestamp = buffer.long
                
                val checksumLength = buffer.get().toInt()
                val checksum = ByteArray(checksumLength).also { buffer.get(it) }
                
                val audioDataLength = buffer.int
                val audioData = ByteArray(audioDataLength).also { buffer.get(it) }
                
                return VoiceFragment(
                    messageID = messageID,
                    fragmentIndex = fragmentIndex,
                    totalFragments = totalFragments,
                    audioData = audioData,
                    checksum = checksum,
                    timestamp = timestamp
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
    
    /**
     * Verify fragment checksum integrity
     */
    fun verifyChecksum(): Boolean {
        return checksum.contentEquals(calculateFragmentChecksum(audioData))
    }
    
    /**
     * Check if this is the last fragment
     */
    fun isLastFragment(): Boolean {
        return fragmentIndex == totalFragments - 1
    }
    
    /**
     * Check if this is the first fragment
     */
    fun isFirstFragment(): Boolean {
        return fragmentIndex == 0
    }
    
    /**
     * Get progress percentage (0.0 to 1.0)
     */
    fun getProgress(): Float {
        return (fragmentIndex + 1).toFloat() / totalFragments.toFloat()
    }
    
    /**
     * Check if fragment has expired (for cleanup)
     */
    fun isExpired(timeoutMs: Long = 30000): Boolean {
        return System.currentTimeMillis() - timestamp > timeoutMs
    }
    
    /**
     * Get estimated total message size
     */
    fun getEstimatedTotalSize(): Int {
        return if (isLastFragment()) {
            (fragmentIndex * MAX_AUDIO_DATA_SIZE) + audioData.size
        } else {
            totalFragments * MAX_AUDIO_DATA_SIZE
        }
    }
    
    /**
     * Serialize fragment to binary format for network transmission
     */
    fun toBinary(): ByteArray {
        val messageIDBytes = messageID.toByteArray()
        
        val buffer = ByteBuffer.allocate(
            1 + // version
            1 + messageIDBytes.size + // messageID
            4 + // fragmentIndex
            4 + // totalFragments
            8 + // timestamp
            1 + checksum.size + // checksum
            4 + audioData.size // audioData
        ).order(ByteOrder.BIG_ENDIAN)
        
        buffer.put(1) // version
        buffer.put(messageIDBytes.size.toByte())
        buffer.put(messageIDBytes)
        buffer.putInt(fragmentIndex)
        buffer.putInt(totalFragments)
        buffer.putLong(timestamp)
        buffer.put(checksum.size.toByte())
        buffer.put(checksum)
        buffer.putInt(audioData.size)
        buffer.put(audioData)
        
        return buffer.array()
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as VoiceFragment
        
        return messageID == other.messageID &&
                fragmentIndex == other.fragmentIndex &&
                totalFragments == other.totalFragments &&
                audioData.contentEquals(other.audioData) &&
                checksum.contentEquals(other.checksum) &&
                timestamp == other.timestamp
    }
    
    override fun hashCode(): Int {
        var result = messageID.hashCode()
        result = 31 * result + fragmentIndex
        result = 31 * result + totalFragments
        result = 31 * result + audioData.contentHashCode()
        result = 31 * result + checksum.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/**
 * Fragment collection manager for tracking reassembly progress
 */
data class FragmentCollection(
    val messageID: String,
    val totalFragments: Int,
    val fragments: MutableMap<Int, VoiceFragment> = mutableMapOf(),
    val startTime: Long = System.currentTimeMillis()
) {
    
    /**
     * Add a fragment to the collection
     */
    fun addFragment(fragment: VoiceFragment): Boolean {
        if (fragment.messageID != messageID || fragment.totalFragments != totalFragments) {
            return false
        }
        
        if (!fragment.verifyChecksum()) {
            return false
        }
        
        fragments[fragment.fragmentIndex] = fragment
        return true
    }
    
    /**
     * Check if collection is complete
     */
    fun isComplete(): Boolean {
        return fragments.size == totalFragments &&
                (0 until totalFragments).all { fragments.containsKey(it) }
    }
    
    /**
     * Get missing fragment indices
     */
    fun getMissingFragments(): List<Int> {
        return (0 until totalFragments).filter { !fragments.containsKey(it) }
    }
    
    /**
     * Get completion percentage
     */
    fun getCompletionPercentage(): Float {
        return fragments.size.toFloat() / totalFragments.toFloat()
    }
    
    /**
     * Check if collection has expired
     */
    fun isExpired(timeoutMs: Long = 30000): Boolean {
        return System.currentTimeMillis() - startTime > timeoutMs
    }
    
    /**
     * Reassemble complete audio data
     */
    fun reassemble(): ByteArray? {
        if (!isComplete()) return null
        return VoiceFragment.reassembleFragments(fragments.values.toList())
    }
} 