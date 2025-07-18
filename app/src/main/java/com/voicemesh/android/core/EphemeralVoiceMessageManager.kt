package com.voicemesh.android.core

import android.content.Context
import android.util.Log
import com.voicemesh.android.model.EphemeralVoiceMessage
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Ephemeral voice message manager - handles message lifecycle with auto-deletion
 * Based on MVP specification for 5-minute expiration and storage limits
 */
class EphemeralVoiceMessageManager(private val context: Context) {
    
    companion object {
        private const val TAG = "EphemeralVoiceMessageManager"
        private const val MAX_STORED_MESSAGES = 10 // Maximum messages per MVP spec
        private const val CLEANUP_INTERVAL_MS = 30000L // Cleanup every 30 seconds
        private const val MAX_STORAGE_SIZE_BYTES = 50_000_000L // 50MB total storage limit
    }
    
    // Stored voice messages (messageID -> EphemeralVoiceMessage)
    private val storedMessages = ConcurrentHashMap<String, EphemeralVoiceMessage>()
    
    // Expiration timers (messageID -> Job)
    private val expirationTimers = ConcurrentHashMap<String, Job>()
    
    // Storage directory for temporary audio files
    private val storageDir = File(context.cacheDir, "voice_messages").apply {
        if (!exists()) mkdirs()
    }
    
    // Callbacks
    private var onMessageExpired: ((String) -> Unit)? = null
    private var onMessageDelivered: ((String) -> Unit)? = null
    private var onStorageLimitReached: (() -> Unit)? = null
    private var onMessageDeleted: ((String, String) -> Unit)? = null
    
    // Background job for periodic cleanup
    private var cleanupJob: Job? = null
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
        cleanupOnStartup()
    }
    
    /**
     * Store a voice message with automatic expiration
     */
    fun storeMessage(message: EphemeralVoiceMessage): Boolean {
        try {
            Log.i(TAG, "Storing voice message ${message.id} (${message.audioData.size} bytes)")
            
            // Check storage limits
            if (!checkStorageLimits(message)) {
                Log.w(TAG, "Storage limits reached, cannot store message ${message.id}")
                onStorageLimitReached?.invoke()
                return false
            }
            
            // Store message
            storedMessages[message.id] = message
            
            // Schedule expiration
            scheduleExpiration(message.id, message.remainingTimeMs())
            
            Log.i(TAG, "Message ${message.id} stored, expires in ${message.remainingTimeMs()}ms")
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store message ${message.id}", e)
            return false
        }
    }
    
    /**
     * Retrieve a stored message
     */
    fun getMessage(messageID: String): EphemeralVoiceMessage? {
        val message = storedMessages[messageID]
        
        if (message != null && message.shouldDelete()) {
            Log.i(TAG, "Message $messageID has expired, removing")
            deleteMessage(messageID, "expired")
            return null
        }
        
        return message
    }
    
    /**
     * Mark a message as delivered and confirm delivery
     */
    fun confirmDelivery(messageID: String): Boolean {
        val message = storedMessages[messageID] ?: return false
        
        Log.i(TAG, "Confirming delivery for message $messageID")
        
        val updatedMessage = message.markDelivered()
        storedMessages[messageID] = updatedMessage
        
        onMessageDelivered?.invoke(messageID)
        
        return true
    }
    
    /**
     * Mark a message as played (triggers immediate deletion)
     */
    fun markMessagePlayed(messageID: String): Boolean {
        val message = storedMessages[messageID] ?: return false
        
        Log.i(TAG, "Message $messageID played, deleting immediately")
        
        deleteMessage(messageID, "played")
        return true
    }
    
    /**
     * Delete a specific message
     */
    fun deleteMessage(messageID: String, reason: String = "manual"): Boolean {
        try {
            val message = storedMessages.remove(messageID)
            if (message == null) {
                Log.w(TAG, "Attempted to delete non-existent message $messageID")
                return false
            }
            
            // Cancel expiration timer
            expirationTimers.remove(messageID)?.cancel()
            
            // Delete any associated storage files
            deleteMessageFiles(messageID)
            
            Log.i(TAG, "Message $messageID deleted (reason: $reason)")
            onMessageDeleted?.invoke(messageID, reason)
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete message $messageID", e)
            return false
        }
    }
    
    /**
     * Get all stored messages
     */
    fun getAllMessages(): List<EphemeralVoiceMessage> {
        // Clean up expired messages first
        val expiredMessages = storedMessages.values.filter { it.shouldDelete() }
        expiredMessages.forEach { deleteMessage(it.id, "expired") }
        
        return storedMessages.values.toList().sortedByDescending { it.timestamp }
    }
    
    /**
     * Get messages for a specific recipient
     */
    fun getMessagesForRecipient(recipientID: String): List<EphemeralVoiceMessage> {
        return getAllMessages().filter { it.recipientID == recipientID }
    }
    
    /**
     * Get messages from a specific sender
     */
    fun getMessagesFromSender(senderID: String): List<EphemeralVoiceMessage> {
        return getAllMessages().filter { it.senderID == senderID }
    }
    
    /**
     * Check if storage limits allow storing a new message
     */
    private fun checkStorageLimits(newMessage: EphemeralVoiceMessage): Boolean {
        // Check message count limit
        if (storedMessages.size >= MAX_STORED_MESSAGES) {
            Log.w(TAG, "Message count limit reached (${storedMessages.size}/$MAX_STORED_MESSAGES)")
            
            // Try to free space by deleting oldest messages
            if (!freeStorageSpace()) {
                return false
            }
        }
        
        // Check total storage size
        val currentStorageSize = calculateTotalStorageSize()
        val newTotalSize = currentStorageSize + newMessage.audioData.size
        
        if (newTotalSize > MAX_STORAGE_SIZE_BYTES) {
            Log.w(TAG, "Storage size limit would be exceeded ($newTotalSize > $MAX_STORAGE_SIZE_BYTES)")
            
            // Try to free space
            if (!freeStorageSpace(newMessage.audioData.size.toLong())) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * Free storage space by deleting oldest messages
     */
    private fun freeStorageSpace(requiredSpace: Long = 0): Boolean {
        val messages = storedMessages.values.sortedBy { it.timestamp }
        var freedSpace = 0L
        var deletedCount = 0
        
        for (message in messages) {
            if (storedMessages.size - deletedCount <= MAX_STORED_MESSAGES / 2) {
                break // Keep at least half the capacity
            }
            
            freedSpace += message.audioData.size
            deleteMessage(message.id, "storage_cleanup")
            deletedCount++
            
            if (freedSpace >= requiredSpace) {
                break
            }
        }
        
        Log.i(TAG, "Freed ${freedSpace} bytes by deleting $deletedCount messages")
        return freedSpace >= requiredSpace || storedMessages.size < MAX_STORED_MESSAGES
    }
    
    /**
     * Calculate total storage size of all messages
     */
    private fun calculateTotalStorageSize(): Long {
        return storedMessages.values.sumOf { it.audioData.size.toLong() }
    }
    
    /**
     * Schedule expiration for a message
     */
    private fun scheduleExpiration(messageID: String, delayMs: Long) {
        // Cancel any existing timer
        expirationTimers[messageID]?.cancel()
        
        // Schedule new expiration
        val expirationJob = managerScope.launch {
            try {
                delay(delayMs)
                
                Log.i(TAG, "Message $messageID expired, deleting")
                deleteMessage(messageID, "expired")
                onMessageExpired?.invoke(messageID)
                
            } catch (e: CancellationException) {
                // Timer was cancelled, ignore
            } catch (e: Exception) {
                Log.e(TAG, "Error in expiration timer for message $messageID", e)
            }
        }
        
        expirationTimers[messageID] = expirationJob
    }
    
    /**
     * Delete any files associated with a message
     */
    private fun deleteMessageFiles(messageID: String) {
        try {
            val messageFiles = storageDir.listFiles { _, name ->
                name.contains(messageID)
            }
            
            messageFiles?.forEach { file ->
                try {
                    file.delete()
                    Log.d(TAG, "Deleted file: ${file.name}")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not delete file: ${file.name}", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting files for message $messageID", e)
        }
    }
    
    /**
     * Start periodic cleanup process
     */
    private fun startPeriodicCleanup() {
        cleanupJob = managerScope.launch {
            while (isActive) {
                try {
                    delay(CLEANUP_INTERVAL_MS)
                    performPeriodicCleanup()
                } catch (e: Exception) {
                    Log.w(TAG, "Error in periodic cleanup", e)
                }
            }
        }
    }
    
    /**
     * Perform periodic cleanup of expired messages
     */
    private fun performPeriodicCleanup() {
        val expiredMessages = storedMessages.values.filter { it.shouldDelete() }
        
        if (expiredMessages.isNotEmpty()) {
            Log.i(TAG, "Periodic cleanup: removing ${expiredMessages.size} expired messages")
            
            expiredMessages.forEach { message ->
                deleteMessage(message.id, "periodic_cleanup")
            }
        }
        
        // Also cleanup orphaned files
        cleanupOrphanedFiles()
    }
    
    /**
     * Cleanup orphaned files (files without corresponding messages)
     */
    private fun cleanupOrphanedFiles() {
        try {
            val allFiles = storageDir.listFiles() ?: return
            val currentMessageIDs = storedMessages.keys
            
            val orphanedFiles = allFiles.filter { file ->
                !currentMessageIDs.any { messageID -> file.name.contains(messageID) }
            }
            
            if (orphanedFiles.isNotEmpty()) {
                Log.i(TAG, "Cleaning up ${orphanedFiles.size} orphaned files")
                
                orphanedFiles.forEach { file ->
                    try {
                        file.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not delete orphaned file: ${file.name}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up orphaned files", e)
        }
    }
    
    /**
     * Cleanup on startup (remove any leftover files)
     */
    private fun cleanupOnStartup() {
        managerScope.launch {
            try {
                Log.i(TAG, "Performing startup cleanup")
                
                // Clear all stored messages (they're ephemeral, don't persist across app restarts)
                storedMessages.clear()
                expirationTimers.values.forEach { it.cancel() }
                expirationTimers.clear()
                
                // Delete all files in storage directory
                storageDir.listFiles()?.forEach { file ->
                    try {
                        file.delete()
                        Log.d(TAG, "Startup cleanup: deleted ${file.name}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not delete file during startup cleanup: ${file.name}", e)
                    }
                }
                
                Log.i(TAG, "Startup cleanup completed")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during startup cleanup", e)
            }
        }
    }
    
    /**
     * Get current statistics
     */
    fun getStatistics(): MessageManagerStatistics {
        val messages = storedMessages.values
        val totalSize = calculateTotalStorageSize()
        val expiredCount = messages.count { it.shouldDelete() }
        
        return MessageManagerStatistics(
            totalMessages = messages.size,
            totalStorageBytes = totalSize,
            expiredMessages = expiredCount,
            averageMessageSize = if (messages.isNotEmpty()) totalSize / messages.size else 0L,
            oldestMessageAgeMs = messages.minOfOrNull { 
                System.currentTimeMillis() - it.timestamp 
            } ?: 0L,
            newestMessageAgeMs = messages.maxOfOrNull { 
                System.currentTimeMillis() - it.timestamp 
            } ?: 0L
        )
    }
    
    /**
     * Set callback for message expiration
     */
    fun setMessageExpiredCallback(callback: (String) -> Unit) {
        onMessageExpired = callback
    }
    
    /**
     * Set callback for message delivery
     */
    fun setMessageDeliveredCallback(callback: (String) -> Unit) {
        onMessageDelivered = callback
    }
    
    /**
     * Set callback for storage limit reached
     */
    fun setStorageLimitReachedCallback(callback: () -> Unit) {
        onStorageLimitReached = callback
    }
    
    /**
     * Set callback for message deletion
     */
    fun setMessageDeletedCallback(callback: (String, String) -> Unit) {
        onMessageDeleted = callback
    }
    
    /**
     * Release all resources
     */
    fun release() {
        cleanupJob?.cancel()
        
        // Cancel all expiration timers
        expirationTimers.values.forEach { it.cancel() }
        expirationTimers.clear()
        
        // Clear all messages
        storedMessages.clear()
        
        managerScope.cancel()
        
        Log.i(TAG, "EphemeralVoiceMessageManager released")
    }
}

/**
 * Message manager statistics
 */
data class MessageManagerStatistics(
    val totalMessages: Int,
    val totalStorageBytes: Long,
    val expiredMessages: Int,
    val averageMessageSize: Long,
    val oldestMessageAgeMs: Long,
    val newestMessageAgeMs: Long
) {
    
    /**
     * Get formatted statistics summary
     */
    fun getSummary(): String {
        return buildString {
            append("Messages: $totalMessages")
            if (totalMessages > 0) {
                append(", Storage: ${totalStorageBytes / 1024}KB")
                append(", Avg size: ${averageMessageSize / 1024}KB")
                if (expiredMessages > 0) {
                    append(", Expired: $expiredMessages")
                }
            }
        }
    }
    
    /**
     * Get storage usage percentage (0.0 to 1.0)
     */
    fun getStorageUsagePercentage(): Float {
        return totalStorageBytes.toFloat() / 50_000_000f // 50MB limit
    }
} 