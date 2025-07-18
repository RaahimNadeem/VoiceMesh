package com.voicemesh.android.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.voicemesh.android.audio.PlaybackState
import com.voicemesh.android.audio.RecordingState
import com.voicemesh.android.core.VoiceMeshService
import com.voicemesh.android.core.VoiceMeshServiceDelegate
import com.voicemesh.android.model.EphemeralVoiceMessage
import com.voicemesh.android.model.VoicePeer
import com.voicemesh.android.services.VoiceMeshNetworkService
import kotlinx.coroutines.launch

/**
 * VoiceMeshViewModel - MVVM pattern implementing VoiceMeshServiceDelegate
 * Manages UI state and coordinates with VoiceMeshService
 */
class VoiceMeshViewModel(application: Application) : AndroidViewModel(application), VoiceMeshServiceDelegate {
    
    companion object {
        private const val TAG = "VoiceMeshViewModel"
    }
    
    private val context: Context = application.applicationContext
    
    // Services
    private var voiceMeshService: VoiceMeshService? = null
    private var networkService: VoiceMeshNetworkService? = null
    private var isServiceBound = false
    
    // UI State
    private val _serviceState = MutableLiveData<ServiceState>()
    val serviceState: LiveData<ServiceState> = _serviceState
    
    private val _connectedPeers = MutableLiveData<List<VoicePeer>>()
    val connectedPeers: LiveData<List<VoicePeer>> = _connectedPeers
    
    private val _recordingState = MutableLiveData<RecordingState>()
    val recordingState: LiveData<RecordingState> = _recordingState
    
    private val _playbackState = MutableLiveData<PlaybackState>()
    val playbackState: LiveData<PlaybackState> = _playbackState
    
    private val _voiceMessages = MutableLiveData<List<EphemeralVoiceMessage>>()
    val voiceMessages: LiveData<List<EphemeralVoiceMessage>> = _voiceMessages
    
    private val _selectedRecipient = MutableLiveData<VoicePeer?>()
    val selectedRecipient: LiveData<VoicePeer?> = _selectedRecipient
    
    private val _recordedAudio = MutableLiveData<RecordedAudio?>()
    val recordedAudio: LiveData<RecordedAudio?> = _recordedAudio
    
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage
    
    private val _networkState = MutableLiveData<NetworkState>()
    val networkState: LiveData<NetworkState> = _networkState
    
    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Network service connected")
            val binder = service as VoiceMeshNetworkService.VoiceMeshBinder
            networkService = binder.getService()
            isServiceBound = true
            
            // Initialize VoiceMesh service with network service
            initializeVoiceMeshService()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "Network service disconnected")
            networkService = null
            isServiceBound = false
        }
    }
    
    init {
        _serviceState.value = ServiceState.INITIALIZING
        _recordingState.value = RecordingState()
        _playbackState.value = PlaybackState()
        _voiceMessages.value = emptyList()
        _connectedPeers.value = emptyList()
        _networkState.value = NetworkState(false, 0, "Initializing")
        
        // Start and bind to network service
        startNetworkService()
    }
    
    /**
     * Start and bind to the network service
     */
    private fun startNetworkService() {
        try {
            val intent = Intent(context, VoiceMeshNetworkService::class.java)
            context.startForegroundService(intent)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "Started network service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start network service", e)
            _serviceState.value = ServiceState.ERROR("Failed to start network service")
        }
    }
    
    /**
     * Initialize VoiceMesh service after network service is ready
     */
    private fun initializeVoiceMeshService() {
        try {
            voiceMeshService = VoiceMeshService(context).apply {
                delegate = this@VoiceMeshViewModel
                networkService = this@VoiceMeshViewModel.networkService
            }
            
            viewModelScope.launch {
                val success = voiceMeshService?.start() ?: false
                if (success) {
                    _serviceState.value = ServiceState.ACTIVE
                    Log.i(TAG, "VoiceMesh service initialized successfully")
                } else {
                    _serviceState.value = ServiceState.ERROR("Failed to initialize VoiceMesh service")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize VoiceMesh service", e)
            _serviceState.value = ServiceState.ERROR("Initialization failed: ${e.message}")
        }
    }
    
    /**
     * Start recording voice message
     */
    fun startRecording() {
        val recipient = _selectedRecipient.value
        if (recipient == null) {
            _errorMessage.value = "Please select a recipient first"
            return
        }
        
        viewModelScope.launch {
            try {
                val success = voiceMeshService?.startRecording(recipient.peerID) ?: false
                if (!success) {
                    _errorMessage.value = "Failed to start recording"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting recording", e)
                _errorMessage.value = "Recording error: ${e.message}"
            }
        }
    }
    
    /**
     * Stop recording
     */
    fun stopRecording() {
        viewModelScope.launch {
            try {
                voiceMeshService?.stopRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recording", e)
                _errorMessage.value = "Stop recording error: ${e.message}"
            }
        }
    }
    
    /**
     * Cancel current recording
     */
    fun cancelRecording() {
        viewModelScope.launch {
            try {
                voiceMeshService?.cancelCurrentRecording()
                _recordedAudio.value = null
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling recording", e)
            }
        }
    }
    
    /**
     * Send recorded voice message
     */
    fun sendRecordedMessage() {
        val recordedAudio = _recordedAudio.value
        val recipient = _selectedRecipient.value
        
        if (recordedAudio == null || recipient == null) {
            _errorMessage.value = "No recorded audio or recipient selected"
            return
        }
        
        viewModelScope.launch {
            try {
                val success = voiceMeshService?.sendVoiceMessage(
                    recordedAudio.audioData,
                    recipient.peerID
                ) ?: false
                
                if (success) {
                    _recordedAudio.value = null // Clear recorded audio after sending
                } else {
                    _errorMessage.value = "Failed to send voice message"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending voice message", e)
                _errorMessage.value = "Send error: ${e.message}"
            }
        }
    }
    
    /**
     * Play voice message
     */
    fun playVoiceMessage(messageID: String) {
        viewModelScope.launch {
            try {
                val success = voiceMeshService?.playVoiceMessage(messageID) ?: false
                if (!success) {
                    _errorMessage.value = "Failed to play voice message"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing voice message", e)
                _errorMessage.value = "Playback error: ${e.message}"
            }
        }
    }
    
    /**
     * Stop current playback
     */
    fun stopPlayback() {
        viewModelScope.launch {
            try {
                voiceMeshService?.stopCurrentPlayback()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping playback", e)
            }
        }
    }
    
    /**
     * Select recipient for voice messages
     */
    fun selectRecipient(peer: VoicePeer?) {
        _selectedRecipient.value = peer
        Log.i(TAG, "Selected recipient: ${peer?.nickname ?: "None"}")
    }
    
    /**
     * Refresh connected peers
     */
    fun refreshPeers() {
        viewModelScope.launch {
            try {
                val peers = voiceMeshService?.getConnectedPeers() ?: emptyList()
                _connectedPeers.value = peers
                
                val networkStats = networkService?.getNetworkStatistics()
                _networkState.value = NetworkState(
                    isActive = networkStats?.isActive ?: false,
                    peerCount = peers.size,
                    status = if (networkStats?.isActive == true) "Connected to ${peers.size} peers" else "Network inactive"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing peers", e)
            }
        }
    }
    
    /**
     * Refresh voice messages
     */
    fun refreshMessages() {
        viewModelScope.launch {
            try {
                val messages = voiceMeshService?.getStoredMessages() ?: emptyList()
                _voiceMessages.value = messages
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing messages", e)
            }
        }
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * Clear recorded audio
     */
    fun clearRecordedAudio() {
        _recordedAudio.value = null
    }
    
    /**
     * Get service statistics
     */
    fun getStatistics(): String {
        return voiceMeshService?.getStatistics()?.getSummary() ?: "Service not available"
    }
    
    // VoiceMeshServiceDelegate implementation
    override fun onServiceStateChanged(isActive: Boolean) {
        viewModelScope.launch {
            _serviceState.value = if (isActive) ServiceState.ACTIVE else ServiceState.INACTIVE
            if (isActive) {
                refreshPeers()
                refreshMessages()
            }
        }
    }
    
    override fun onServiceError(error: String) {
        viewModelScope.launch {
            _serviceState.value = ServiceState.ERROR(error)
            _errorMessage.value = error
        }
    }
    
    override fun onPeerDiscovered(peer: VoicePeer) {
        viewModelScope.launch {
            refreshPeers()
        }
    }
    
    override fun onPeerLost(peerID: String) {
        viewModelScope.launch {
            refreshPeers()
            // Clear selection if selected peer is lost
            if (_selectedRecipient.value?.peerID == peerID) {
                _selectedRecipient.value = null
            }
        }
    }
    
    override fun onRecordingStateChanged(state: RecordingState) {
        viewModelScope.launch {
            _recordingState.value = state
            if (state.error != null) {
                _errorMessage.value = state.error
            }
        }
    }
    
    override fun onVoiceMessageRecorded(audioData: ByteArray, durationMs: Long) {
        viewModelScope.launch {
            _recordedAudio.value = RecordedAudio(audioData, durationMs)
        }
    }
    
    override fun onVoiceMessageSending(messageID: String, recipientID: String) {
        viewModelScope.launch {
            // Could show sending status in UI
            Log.i(TAG, "Sending voice message $messageID to $recipientID")
        }
    }
    
    override fun onPlaybackStateChanged(state: PlaybackState) {
        viewModelScope.launch {
            _playbackState.value = state
            if (state.error != null) {
                _errorMessage.value = state.error
            }
        }
    }
    
    override fun onVoiceMessageReceived(message: EphemeralVoiceMessage) {
        viewModelScope.launch {
            refreshMessages()
            // Could show notification for new message
            Log.i(TAG, "Received voice message ${message.id}")
        }
    }
    
    override fun onVoiceMessagePlayed(messageID: String) {
        viewModelScope.launch {
            refreshMessages() // Message should be deleted after playing
        }
    }
    
    override fun onVoiceMessageDelivered(messageID: String) {
        viewModelScope.launch {
            // Could update UI to show delivery status
            Log.i(TAG, "Voice message delivered: $messageID")
        }
    }
    
    override fun onVoiceMessageFailed(messageID: String, error: String) {
        viewModelScope.launch {
            _errorMessage.value = "Message failed: $error"
        }
    }
    
    override fun onVoiceMessageExpired(messageID: String) {
        viewModelScope.launch {
            refreshMessages()
        }
    }
    
    override fun onNetworkStateChanged(isActive: Boolean) {
        viewModelScope.launch {
            refreshPeers()
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        
        // Release resources
        viewModelScope.launch {
            try {
                voiceMeshService?.release()
                
                if (isServiceBound) {
                    context.unbindService(serviceConnection)
                    isServiceBound = false
                }
                
                // Stop network service
                val intent = Intent(context, VoiceMeshNetworkService::class.java)
                context.stopService(intent)
                
                Log.i(TAG, "VoiceMeshViewModel cleaned up")
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }
}

/**
 * Service state enum
 */
sealed class ServiceState {
    object INITIALIZING : ServiceState()
    object ACTIVE : ServiceState()
    object INACTIVE : ServiceState()
    data class ERROR(val message: String) : ServiceState()
}

/**
 * Network state data class
 */
data class NetworkState(
    val isActive: Boolean,
    val peerCount: Int,
    val status: String
)

/**
 * Recorded audio data class
 */
data class RecordedAudio(
    val audioData: ByteArray,
    val durationMs: Long
) {
    
    /**
     * Get formatted duration string
     */
    fun getFormattedDuration(): String {
        val seconds = durationMs / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }
    
    /**
     * Get size in KB
     */
    fun getSizeKB(): Int {
        return audioData.size / 1024
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as RecordedAudio
        
        return audioData.contentEquals(other.audioData) && durationMs == other.durationMs
    }
    
    override fun hashCode(): Int {
        var result = audioData.contentHashCode()
        result = 31 * result + durationMs.hashCode()
        return result
    }
} 