package com.voicemesh.android.core

import android.util.Log
import com.voicemesh.android.model.EphemeralVoiceMessage
import com.voicemesh.android.model.VoiceFragment
import com.voicemesh.android.model.FragmentCollection
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Voice fragment manager for VoiceMesh - handles fragmentation and reassembly
 * Based on MVP specification for BLE-sized chunks with 30-second reassembly timeout
 */
class VoiceFragmentManager {
    
    companion object {
        private const val TAG = "VoiceFragmentManager"
        private const val REASSEMBLY_TIMEOUT_MS = 30000L // 30 seconds as per MVP
        private const val CLEANUP_INTERVAL_MS = 10000L // Cleanup every 10 seconds
        private const val MAX_CONCURRENT_COLLECTIONS = 50 // Prevent memory exhaustion
    }
    
    // Fragment collections being reassembled
    private val fragmentCollections = ConcurrentHashMap<String, FragmentCollection>()
    
    // Reassembly completion callbacks
    private var onMessageReassembled: ((String, ByteArray) -> Unit)? = null
    private var onReassemblyFailed: ((String, String) -> Unit)? = null
    private var onFragmentReceived: ((String, Int, Int) -> Unit)? = null
    
    // Cleanup job
    private var cleanupJob: Job? = null
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Fragment a voice message into BLE-sized chunks
     */
    fun fragmentVoiceMessage(message: EphemeralVoiceMessage): List<VoiceFragment> {
        Log.i(TAG, "Fragmenting voice message ${message.id}, size: ${message.audioData.size} bytes")
        
        val fragments = VoiceFragment.fragmentVoiceMessage(message)
        
        Log.i(TAG, "Created ${fragments.size} fragments for message ${message.id}")
        return fragments
    }
    
    /**
     * Add a received fragment to the reassembly process
     */
    fun addFragment(fragment: VoiceFragment): FragmentReceptionResult {
        try {
            Log.d(TAG, "Received fragment ${fragment.fragmentIndex}/${fragment.totalFragments} for message ${fragment.messageID}")
            
            // Check if we're at capacity
            if (fragmentCollections.size >= MAX_CONCURRENT_COLLECTIONS) {
                Log.w(TAG, "Maximum concurrent fragment collections reached, rejecting fragment")
                return FragmentReceptionResult.REJECTED_CAPACITY_FULL
            }
            
            // Get or create fragment collection
            val collection = fragmentCollections.getOrPut(fragment.messageID) {
                Log.i(TAG, "Starting new fragment collection for message ${fragment.messageID}")
                FragmentCollection(fragment.messageID, fragment.totalFragments)
            }
            
            // Validate fragment belongs to this collection
            if (collection.totalFragments != fragment.totalFragments) {
                Log.w(TAG, "Fragment total count mismatch for message ${fragment.messageID}")
                return FragmentReceptionResult.REJECTED_INVALID
            }
            
            // Add fragment to collection
            val added = collection.addFragment(fragment)
            if (!added) {
                Log.w(TAG, "Failed to add fragment ${fragment.fragmentIndex} to collection ${fragment.messageID}")
                return FragmentReceptionResult.REJECTED_INVALID
            }
            
            // Notify progress
            onFragmentReceived?.invoke(
                fragment.messageID,
                collection.fragments.size,
                collection.totalFragments
            )
            
            Log.d(TAG, "Fragment added. Progress: ${collection.fragments.size}/${collection.totalFragments}")
            
            // Check if collection is complete
            if (collection.isComplete()) {
                Log.i(TAG, "All fragments received for message ${fragment.messageID}, reassembling...")
                
                val audioData = collection.reassemble()
                if (audioData != null) {
                    Log.i(TAG, "Successfully reassembled message ${fragment.messageID}, size: ${audioData.size} bytes")
                    
                    // Remove from pending collections
                    fragmentCollections.remove(fragment.messageID)
                    
                    // Notify completion
                    onMessageReassembled?.invoke(fragment.messageID, audioData)
                    
                    return FragmentReceptionResult.MESSAGE_COMPLETE
                } else {
                    Log.e(TAG, "Failed to reassemble complete message ${fragment.messageID}")
                    fragmentCollections.remove(fragment.messageID)
                    onReassemblyFailed?.invoke(fragment.messageID, "Reassembly failed")
                    return FragmentReceptionResult.REASSEMBLY_FAILED
                }
            }
            
            return FragmentReceptionResult.FRAGMENT_ADDED
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing fragment", e)
            return FragmentReceptionResult.ERROR
        }
    }
    
    /**
     * Get reassembly progress for a message
     */
    fun getReassemblyProgress(messageID: String): ReassemblyProgress? {
        val collection = fragmentCollections[messageID] ?: return null
        
        return ReassemblyProgress(
            messageID = messageID,
            receivedFragments = collection.fragments.size,
            totalFragments = collection.totalFragments,
            completionPercentage = collection.getCompletionPercentage(),
            missingFragments = collection.getMissingFragments(),
            elapsedTimeMs = System.currentTimeMillis() - collection.startTime,
            isExpired = collection.isExpired(REASSEMBLY_TIMEOUT_MS)
        )
    }
    
    /**
     * Get all pending reassembly operations
     */
    fun getAllPendingReassemblies(): List<ReassemblyProgress> {
        return fragmentCollections.values.map { collection ->
            ReassemblyProgress(
                messageID = collection.messageID,
                receivedFragments = collection.fragments.size,
                totalFragments = collection.totalFragments,
                completionPercentage = collection.getCompletionPercentage(),
                missingFragments = collection.getMissingFragments(),
                elapsedTimeMs = System.currentTimeMillis() - collection.startTime,
                isExpired = collection.isExpired(REASSEMBLY_TIMEOUT_MS)
            )
        }
    }
    
    /**
     * Cancel reassembly for a specific message
     */
    fun cancelReassembly(messageID: String): Boolean {
        val collection = fragmentCollections.remove(messageID)
        if (collection != null) {
            Log.i(TAG, "Cancelled reassembly for message $messageID")
            onReassemblyFailed?.invoke(messageID, "Reassembly cancelled")
            return true
        }
        return false
    }
    
    /**
     * Check if a message is currently being reassembled
     */
    fun isReassembling(messageID: String): Boolean {
        return fragmentCollections.containsKey(messageID)
    }
    
    /**
     * Get missing fragments for a message (for retransmission requests)
     */
    fun getMissingFragments(messageID: String): List<Int> {
        return fragmentCollections[messageID]?.getMissingFragments() ?: emptyList()
    }
    
    /**
     * Set callback for when a message is successfully reassembled
     */
    fun setMessageReassembledCallback(callback: (String, ByteArray) -> Unit) {
        onMessageReassembled = callback
    }
    
    /**
     * Set callback for when reassembly fails
     */
    fun setReassemblyFailedCallback(callback: (String, String) -> Unit) {
        onReassemblyFailed = callback
    }
    
    /**
     * Set callback for fragment reception progress
     */
    fun setFragmentReceivedCallback(callback: (String, Int, Int) -> Unit) {
        onFragmentReceived = callback
    }
    
    /**
     * Start periodic cleanup of expired fragment collections
     */
    private fun startPeriodicCleanup() {
        cleanupJob = managerScope.launch {
            while (isActive) {
                try {
                    delay(CLEANUP_INTERVAL_MS)
                    cleanupExpiredCollections()
                } catch (e: Exception) {
                    Log.w(TAG, "Error in periodic cleanup", e)
                }
            }
        }
    }
    
    /**
     * Cleanup expired fragment collections
     */
    private fun cleanupExpiredCollections() {
        val expiredCollections = fragmentCollections.values.filter { 
            it.isExpired(REASSEMBLY_TIMEOUT_MS) 
        }
        
        if (expiredCollections.isNotEmpty()) {
            Log.i(TAG, "Cleaning up ${expiredCollections.size} expired fragment collections")
            
            for (collection in expiredCollections) {
                fragmentCollections.remove(collection.messageID)
                
                Log.w(TAG, "Fragment collection expired for message ${collection.messageID} " +
                       "(${collection.fragments.size}/${collection.totalFragments} fragments received)")
                
                onReassemblyFailed?.invoke(
                    collection.messageID, 
                    "Reassembly timeout after ${REASSEMBLY_TIMEOUT_MS}ms"
                )
            }
        }
    }
    
    /**
     * Get current statistics
     */
    fun getStatistics(): FragmentManagerStatistics {
        val collections = fragmentCollections.values
        val totalFragmentsReceived = collections.sumOf { it.fragments.size }
        val totalFragmentsExpected = collections.sumOf { it.totalFragments }
        
        return FragmentManagerStatistics(
            pendingCollections = collections.size,
            totalFragmentsReceived = totalFragmentsReceived,
            totalFragmentsExpected = totalFragmentsExpected,
            averageCompletionPercentage = if (collections.isNotEmpty()) {
                collections.map { it.getCompletionPercentage() }.average().toFloat()
            } else 0f,
            oldestCollectionAgeMs = collections.minOfOrNull { 
                System.currentTimeMillis() - it.startTime 
            } ?: 0L
        )
    }
    
    /**
     * Force cleanup of all pending collections
     */
    fun clearAllPendingCollections() {
        val count = fragmentCollections.size
        fragmentCollections.clear()
        Log.i(TAG, "Cleared $count pending fragment collections")
    }
    
    /**
     * Release resources
     */
    fun release() {
        cleanupJob?.cancel()
        clearAllPendingCollections()
        managerScope.cancel()
        Log.i(TAG, "VoiceFragmentManager released")
    }
}

/**
 * Result of fragment reception
 */
enum class FragmentReceptionResult {
    FRAGMENT_ADDED,         // Fragment added to collection
    MESSAGE_COMPLETE,       // All fragments received, message reassembled
    REASSEMBLY_FAILED,     // Complete but reassembly failed
    REJECTED_INVALID,      // Fragment rejected due to validation failure
    REJECTED_CAPACITY_FULL, // Fragment rejected due to capacity limits
    ERROR                  // General error processing fragment
}

/**
 * Reassembly progress information
 */
data class ReassemblyProgress(
    val messageID: String,
    val receivedFragments: Int,
    val totalFragments: Int,
    val completionPercentage: Float,
    val missingFragments: List<Int>,
    val elapsedTimeMs: Long,
    val isExpired: Boolean
) {
    
    /**
     * Get formatted progress string
     */
    fun getProgressString(): String {
        return "$receivedFragments/$totalFragments (${(completionPercentage * 100).toInt()}%)"
    }
    
    /**
     * Get formatted elapsed time
     */
    fun getElapsedTimeString(): String {
        val seconds = elapsedTimeMs / 1000
        return "${seconds}s"
    }
    
    /**
     * Check if reassembly is near timeout
     */
    fun isNearTimeout(timeoutMs: Long = 30000L): Boolean {
        return elapsedTimeMs > (timeoutMs * 0.8) // 80% of timeout
    }
}

/**
 * Fragment manager statistics
 */
data class FragmentManagerStatistics(
    val pendingCollections: Int,
    val totalFragmentsReceived: Int,
    val totalFragmentsExpected: Int,
    val averageCompletionPercentage: Float,
    val oldestCollectionAgeMs: Long
) {
    
    /**
     * Get formatted statistics summary
     */
    fun getSummary(): String {
        return buildString {
            append("Pending: $pendingCollections collections")
            if (pendingCollections > 0) {
                append(", Progress: ${(averageCompletionPercentage * 100).toInt()}%")
                append(", Fragments: $totalFragmentsReceived/$totalFragmentsExpected")
                append(", Oldest: ${oldestCollectionAgeMs / 1000}s")
            }
        }
    }
} 