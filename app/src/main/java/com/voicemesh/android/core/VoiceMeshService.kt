package com.voicemesh.android.core

import android.content.Context
import android.util.Log
import com.voicemesh.android.audio.*
import com.voicemesh.android.model.*
import com.voicemesh.android.protocol.*
import com.voicemesh.android.services.VoiceMeshDelegate
import com.voicemesh.android.services.VoiceMeshNetworkService
import kotlinx.coroutines.*
import kotlin.random.Random

/**
 * VoiceMeshService - Main coordinator for VoiceMesh functionality
 * Based on MVP specification integrating audio, messaging, networking, and UI
 */
class VoiceMeshService(
    private val context: Context
) : VoiceMeshDelegate {
    
    companion object {
        private const val TAG = "VoiceMeshService"
        private const val MY_PEER_ID = "voicemesh_self"
    }
    
    // Core components
    private val voiceRecorder = VoiceRecorder(context)
    private val voiceCompressor = VoiceCompressor(context)
    private val voicePlayer = VoicePlayer(context)
    private val fragmentManager = VoiceFragmentManager()
    private val messageManager = EphemeralVoiceMessageManager(context)
    
    // Network service reference (injected)
    var networkService: VoiceMeshNetworkService? = null
    
    // State
    private var isActive = false
    private val myPeerID = MY_PEER_ID
    
    // Callbacks for UI
    var delegate: VoiceMeshServiceDelegate? = null
    
    // Coroutine scope
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    init {
        setupComponentCallbacks()
    }
    
    /**
     * Start VoiceMesh service
     */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isActive) {
                Log.w(TAG, "Service already active")
                return@withContext true
            }
            
            Log.i(TAG, "Starting VoiceMesh service")
            isActive = true
            
            // Setup network callbacks
            networkService?.let { network ->
                network.onPeerDiscovered = { peer -> onPeerDiscovered(peer) }
                network.onPeerLost = { peerID -> onPeerLost(peerID) }
                network.onPacketReceived = { packet -> onVoicePacketReceived(packet) }
                network.onTransmissionComplete = { messageID, success ->
                    if (success) {
                        onVoiceMessageDelivered(messageID)
                    } else {
                        onVoiceMessageFailed(messageID, "Network transmission failed")
                    }
                }
                network.onNetworkStateChanged = { active -> onNetworkStateChanged(active) }
            }
            
            delegate?.onServiceStateChanged(true)
            Log.i(TAG, "VoiceMesh service started successfully")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VoiceMesh service", e)
            isActive = false
            delegate?.onServiceError("Failed to start service: ${e.message}")
            false
        }
    }
    
    /**
     * Stop VoiceMesh service
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            if (!isActive) {
                Log.w(TAG, "Service already stopped")
                return@withContext
            }
            
            Log.i(TAG, "Stopping VoiceMesh service")
            isActive = false
            
            // Stop any ongoing recording or playback
            cancelCurrentRecording()
            stopCurrentPlayback()
            
            delegate?.onServiceStateChanged(false)
            Log.i(TAG, "VoiceMesh service stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VoiceMesh service", e)
        }
    }
    
    /**
     * Start recording voice message for recipient
     */
    suspend fun startRecording(recipientID: String): Boolean {
        if (!isActive) {
            Log.w(TAG, "Cannot start recording - service not active")
            return false
        }
        
        Log.i(TAG, "Starting voice recording for recipient: $recipientID")
        
        val success = voiceRecorder.startRecording()
        if (success) {
            delegate?.onRecordingStateChanged(
                RecordingState(
                    isRecording = true,
                    currentLevel = 0f,
                    durationMs = 0L,
                    remainingMs = 60000L
                )
            )
        }
        
        return success
    }
    
    /**
     * Stop recording and send voice message
     */
    suspend fun stopRecording(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Stopping voice recording")
            
            val audioFile = voiceRecorder.stopRecording()
            if (audioFile == null) {
                Log.w(TAG, "No audio file produced from recording")
                delegate?.onRecordingStateChanged(
                    RecordingState(error = "No audio recorded")
                )
                return@withContext false
            }
            
            // Compress audio
            val compressionResult = voiceCompressor.compressAudio(audioFile)
            if (!compressionResult.isSuccessful()) {
                Log.e(TAG, "Audio compression failed: ${compressionResult.error}")
                delegate?.onRecordingStateChanged(
                    RecordingState(error = "Compression failed")
                )
                return@withContext false
            }
            
            // Clean up temporary file
            audioFile.delete()
            
            Log.i(TAG, "Recording completed successfully, compressed from ${compressionResult.originalSize} to ${compressionResult.compressedSize} bytes")
            
            delegate?.onRecordingStateChanged(
                RecordingState(
                    isRecording = false,
                    durationMs = compressionResult.durationMs
                )
            )
            
            // Store compressed audio for manual sending
            delegate?.onVoiceMessageRecorded(
                compressionResult.compressedData,
                compressionResult.durationMs
            )
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            delegate?.onRecordingStateChanged(
                RecordingState(error = "Recording failed: ${e.message}")
            )
            false
        }
    }
    
    /**
     * Send voice message to recipient
     */
    suspend fun sendVoiceMessage(
        audioData: ByteArray,
        recipientID: String,
        compressionType: CompressionType = CompressionType.AAC_LC
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Sending voice message to $recipientID (${audioData.size} bytes)")
            
            // Create ephemeral voice message
            val message = EphemeralVoiceMessage.create(
                senderID = myPeerID,
                recipientID = recipientID,
                audioData = audioData,
                compressionType = compressionType
            )
            
            if (message == null) {
                Log.e(TAG, "Failed to create voice message - audio too large")
                delegate?.onVoiceMessageFailed("Message too large", "Audio exceeds size limit")
                return@withContext false
            }
            
            // Store message locally
            if (!messageManager.storeMessage(message)) {
                Log.e(TAG, "Failed to store voice message locally")
                delegate?.onVoiceMessageFailed(message.id, "Storage failed")
                return@withContext false
            }
            
            // Fragment message for transmission
            val fragments = fragmentManager.fragmentVoiceMessage(message)
            Log.i(TAG, "Fragmented message ${message.id} into ${fragments.size} fragments")
            
            // Send fragments over network
            sendVoiceMessageFragments(message, fragments)
            
            delegate?.onVoiceMessageSending(message.id, recipientID)
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send voice message", e)
            delegate?.onVoiceMessageFailed("send_error", "Send failed: ${e.message}")
            false
        }
    }
    
    /**
     * Play received voice message
     */
    suspend fun playVoiceMessage(messageID: String): Boolean {
        try {
            Log.i(TAG, "Playing voice message: $messageID")
            
            val message = messageManager.getMessage(messageID)
            if (message == null) {
                Log.w(TAG, "Voice message not found: $messageID")
                delegate?.onVoiceMessageFailed(messageID, "Message not found")
                return false
            }
            
            // Decompress audio if needed
            val decompressedResult = voiceCompressor.decompressAudio(
                message.audioData,
                message.compressionType
            )
            
            if (!decompressedResult.success) {
                Log.e(TAG, "Failed to decompress audio: ${decompressedResult.error}")
                delegate?.onVoiceMessageFailed(messageID, "Decompression failed")
                return false
            }
            
            // Play audio (ephemeral - will delete after playing)
            val playbackSuccess = voicePlayer.playVoiceMessage(
                decompressedResult.audioData,
                messageID
            )
            
            if (playbackSuccess) {
                // Mark message as played (triggers deletion)
                messageManager.markMessagePlayed(messageID)
                delegate?.onVoiceMessagePlayed(messageID)
                
                // Send delivery confirmation
                sendDeliveryConfirmation(message)
            }
            
            return playbackSuccess
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play voice message", e)
            delegate?.onVoiceMessageFailed(messageID, "Playback failed: ${e.message}")
            return false
        }
    }
    
    /**
     * Cancel current recording
     */
    suspend fun cancelCurrentRecording() {
        try {
            if (voiceRecorder.isRecording()) {
                Log.i(TAG, "Cancelling current recording")
                voiceRecorder.cancelRecording()
                delegate?.onRecordingStateChanged(RecordingState())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling recording", e)
        }
    }
    
    /**
     * Stop current playback
     */
    suspend fun stopCurrentPlayback() {
        try {
            if (voicePlayer.isPlaying()) {
                Log.i(TAG, "Stopping current playback")
                voicePlayer.stopPlayback()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping playback", e)
        }
    }
    
    /**
     * Get connected voice-capable peers
     */
    fun getConnectedPeers(): List<VoicePeer> {
        return networkService?.getConnectedPeers() ?: emptyList()
    }
    
    /**
     * Get stored voice messages
     */
    fun getStoredMessages(): List<EphemeralVoiceMessage> {
        return messageManager.getAllMessages()
    }
    
    /**
     * Get service statistics
     */
    fun getStatistics(): VoiceMeshStatistics {
        val networkStats = networkService?.getNetworkStatistics()
        val messageStats = messageManager.getStatistics()
        val fragmentStats = fragmentManager.getStatistics()
        
        return VoiceMeshStatistics(
            isActive = isActive,
            connectedPeers = networkStats?.connectedPeers ?: 0,
            storedMessages = messageStats.totalMessages,
            pendingFragments = fragmentStats.pendingCollections,
            storageUsage = messageStats.getStorageUsagePercentage()
        )
    }
    
    /**
     * Send voice message fragments over network
     */
    private suspend fun sendVoiceMessageFragments(
        message: EphemeralVoiceMessage,
        fragments: List<VoiceFragment>
    ) = withContext(Dispatchers.IO) {
        try {
            val recipientIDBytes = message.recipientID.toByteArray()
            val senderIDBytes = message.senderID.toByteArray()
            
            // Send start packet
            val startPacket = VoicePacket.createStartPacket(
                messageID = message.id,
                senderID = senderIDBytes,
                recipientID = recipientIDBytes,
                totalFragments = fragments.size,
                compressionType = message.compressionType.value,
                expirationTime = message.expirationTime.toULong()
            )
            
            networkService?.sendPacket(startPacket)
            
            // Send fragment packets
            for (fragment in fragments) {
                val fragmentPacket = VoicePacket.createFragmentPacket(
                    messageID = fragment.messageID,
                    senderID = senderIDBytes,
                    recipientID = recipientIDBytes,
                    fragmentIndex = fragment.fragmentIndex,
                    totalFragments = fragment.totalFragments,
                    fragmentData = fragment.audioData,
                    audioChecksum = fragment.checksum
                )
                
                networkService?.sendPacket(fragmentPacket)
                
                // Small delay between fragments to avoid overwhelming the network
                delay(50)
            }
            
            // Send end packet
            val endPacket = VoicePacket.createEndPacket(
                messageID = message.id,
                senderID = senderIDBytes,
                recipientID = recipientIDBytes,
                totalFragments = fragments.size
            )
            
            networkService?.sendPacket(endPacket)
            
            Log.i(TAG, "Sent ${fragments.size} fragments for message ${message.id}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send voice message fragments", e)
            delegate?.onVoiceMessageFailed(message.id, "Fragment transmission failed")
        }
    }
    
    /**
     * Send delivery confirmation for received message
     */
    private suspend fun sendDeliveryConfirmation(message: EphemeralVoiceMessage) {
        try {
            val deliveredPacket = VoicePacket.createDeliveredPacket(
                messageID = message.id,
                senderID = message.recipientID.toByteArray(),
                recipientID = message.senderID.toByteArray()
            )
            
            networkService?.sendPacket(deliveredPacket)
            Log.d(TAG, "Sent delivery confirmation for message ${message.id}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send delivery confirmation", e)
        }
    }
    
    /**
     * Setup callbacks for components
     */
    private fun setupComponentCallbacks() {
        // Voice recorder callbacks
        voiceRecorder.onLevelUpdate = { level ->
            serviceScope.launch {
                delegate?.onRecordingStateChanged(
                    RecordingState(
                        isRecording = true,
                        currentLevel = level,
                        durationMs = voiceRecorder.getCurrentDurationMs(),
                        remainingMs = voiceRecorder.getRemainingDurationMs()
                    )
                )
            }
        }
        
        voiceRecorder.onError = { error ->
            serviceScope.launch {
                delegate?.onRecordingStateChanged(RecordingState(error = error))
            }
        }
        
        // Voice player callbacks
        voicePlayer.onPlaybackStarted = {
            serviceScope.launch {
                delegate?.onPlaybackStateChanged(
                    PlaybackState(isPlaying = true)
                )
            }
        }
        
        voicePlayer.onPlaybackProgress = { progress ->
            serviceScope.launch {
                delegate?.onPlaybackStateChanged(
                    PlaybackState(
                        isPlaying = true,
                        progress = progress,
                        positionMs = voicePlayer.getCurrentPositionMs(),
                        durationMs = voicePlayer.getDurationMs()
                    )
                )
            }
        }
        
        voicePlayer.onPlaybackCompleted = {
            serviceScope.launch {
                delegate?.onPlaybackStateChanged(PlaybackState())
            }
        }
        
        voicePlayer.onPlaybackError = { error ->
            serviceScope.launch {
                delegate?.onPlaybackStateChanged(PlaybackState(error = error))
            }
        }
        
        // Fragment manager callbacks
        fragmentManager.setMessageReassembledCallback { messageID, audioData ->
            serviceScope.launch {
                handleReassembledMessage(messageID, audioData)
            }
        }
        
        fragmentManager.setReassemblyFailedCallback { messageID, reason ->
            serviceScope.launch {
                Log.w(TAG, "Fragment reassembly failed for $messageID: $reason")
                delegate?.onVoiceMessageFailed(messageID, "Reassembly failed: $reason")
            }
        }
        
        // Message manager callbacks
        messageManager.setMessageExpiredCallback { messageID ->
            serviceScope.launch {
                delegate?.onVoiceMessageExpired(messageID)
            }
        }
        
        messageManager.setMessageDeliveredCallback { messageID ->
            serviceScope.launch {
                delegate?.onVoiceMessageDelivered(messageID)
            }
        }
    }
    
    /**
     * Handle reassembled voice message
     */
    private suspend fun handleReassembledMessage(messageID: String, audioData: ByteArray) {
        try {
            Log.i(TAG, "Handling reassembled message $messageID (${audioData.size} bytes)")
            
            // Create ephemeral message from reassembled data
            // Note: In a real implementation, we'd need more metadata from the start packet
            val message = EphemeralVoiceMessage.create(
                senderID = "unknown", // Would be from start packet
                recipientID = myPeerID,
                audioData = audioData,
                compressionType = CompressionType.AAC_LC
            )
            
            if (message != null) {
                messageManager.storeMessage(message)
                delegate?.onVoiceMessageReceived(message)
                Log.i(TAG, "Stored reassembled voice message ${message.id}")
            } else {
                Log.e(TAG, "Failed to create message from reassembled data")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling reassembled message", e)
        }
    }
    
    // VoiceMeshDelegate implementation
    override fun onPeerDiscovered(peer: VoicePeer) {
        serviceScope.launch {
            Log.i(TAG, "Peer discovered: ${peer.nickname} (${peer.peerID})")
            delegate?.onPeerDiscovered(peer)
        }
    }
    
    override fun onPeerLost(peerID: String) {
        serviceScope.launch {
            Log.i(TAG, "Peer lost: $peerID")
            delegate?.onPeerLost(peerID)
        }
    }
    
    override fun onVoicePacketReceived(packet: VoicePacket) {
        serviceScope.launch {
            Log.d(TAG, "Received ${packet.getTypeDescription()} packet ${packet.messageID}")
            
            when (packet.type) {
                VoiceMessageType.VOICE_MESSAGE_FRAGMENT -> {
                    val fragment = VoiceFragment(
                        messageID = packet.messageID,
                        fragmentIndex = packet.fragmentIndex,
                        totalFragments = packet.totalFragments,
                        audioData = packet.fragmentData,
                        checksum = packet.audioChecksum,
                        timestamp = packet.timestamp.toLong()
                    )
                    
                    fragmentManager.addFragment(fragment)
                }
                VoiceMessageType.VOICE_MESSAGE_DELIVERED -> {
                    messageManager.confirmDelivery(packet.messageID)
                }
                VoiceMessageType.VOICE_MESSAGE_DELETE -> {
                    messageManager.deleteMessage(packet.messageID, "remote_delete")
                }
                else -> {
                    Log.d(TAG, "Ignoring packet type: ${packet.type}")
                }
            }
        }
    }
    
    override fun onVoiceMessageDelivered(messageID: String) {
        serviceScope.launch {
            Log.i(TAG, "Voice message delivered: $messageID")
            delegate?.onVoiceMessageDelivered(messageID)
        }
    }
    
    override fun onVoiceMessageFailed(messageID: String, error: String) {
        serviceScope.launch {
            Log.w(TAG, "Voice message failed: $messageID - $error")
            delegate?.onVoiceMessageFailed(messageID, error)
        }
    }
    
    override fun onNetworkStateChanged(isActive: Boolean) {
        serviceScope.launch {
            Log.i(TAG, "Network state changed: $isActive")
            delegate?.onNetworkStateChanged(isActive)
        }
    }
    
    /**
     * Release all resources
     */
    fun release() {
        serviceScope.launch {
            stop()
            
            voiceRecorder.release()
            voicePlayer.release()
            fragmentManager.release()
            messageManager.release()
            
            serviceScope.cancel()
            Log.i(TAG, "VoiceMeshService resources released")
        }
    }
}

/**
 * VoiceMesh service delegate for UI callbacks
 */
interface VoiceMeshServiceDelegate {
    fun onServiceStateChanged(isActive: Boolean)
    fun onServiceError(error: String)
    
    fun onPeerDiscovered(peer: VoicePeer)
    fun onPeerLost(peerID: String)
    
    fun onRecordingStateChanged(state: RecordingState)
    fun onVoiceMessageRecorded(audioData: ByteArray, durationMs: Long)
    fun onVoiceMessageSending(messageID: String, recipientID: String)
    
    fun onPlaybackStateChanged(state: PlaybackState)
    fun onVoiceMessageReceived(message: EphemeralVoiceMessage)
    fun onVoiceMessagePlayed(messageID: String)
    
    fun onVoiceMessageDelivered(messageID: String)
    fun onVoiceMessageFailed(messageID: String, error: String)
    fun onVoiceMessageExpired(messageID: String)
    
    fun onNetworkStateChanged(isActive: Boolean)
}

/**
 * VoiceMesh service statistics
 */
data class VoiceMeshStatistics(
    val isActive: Boolean,
    val connectedPeers: Int,
    val storedMessages: Int,
    val pendingFragments: Int,
    val storageUsage: Float
) {
    
    /**
     * Get formatted statistics summary
     */
    fun getSummary(): String {
        return buildString {
            if (isActive) {
                append("Active: $connectedPeers peers")
                if (storedMessages > 0) {
                    append(", $storedMessages messages")
                }
                if (pendingFragments > 0) {
                    append(", $pendingFragments fragments")
                }
                append(", Storage: ${(storageUsage * 100).toInt()}%")
            } else {
                append("Inactive")
            }
        }
    }
} 