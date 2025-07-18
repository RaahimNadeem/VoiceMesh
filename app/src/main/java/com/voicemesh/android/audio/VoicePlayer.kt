package com.voicemesh.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/**
 * Voice player for VoiceMesh - ephemeral playback with automatic deletion after playing
 * Based on MVP specification for self-destructing voice messages
 */
class VoicePlayer(private val context: Context) {
    
    companion object {
        private const val TAG = "VoicePlayer"
        private const val TEMP_FILE_PREFIX = "voice_playback_"
        private const val TEMP_FILE_SUFFIX = ".m4a"
    }
    
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var currentTempFile: File? = null
    private var playbackJob: Job? = null
    
    // Playback callbacks
    var onPlaybackStarted: (() -> Unit)? = null
    var onPlaybackProgress: ((Float) -> Unit)? = null
    var onPlaybackCompleted: (() -> Unit)? = null
    var onPlaybackError: ((String) -> Unit)? = null
    
    /**
     * Play voice message from audio data (ephemeral - deletes after playing)
     */
    suspend fun playVoiceMessage(
        audioData: ByteArray,
        messageId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isPlaying) {
                Log.w(TAG, "Already playing audio")
                stopPlayback()
            }
            
            Log.i(TAG, "Starting playback for message: $messageId")
            
            // Create temporary file for playback
            val tempFile = createTempAudioFile(messageId)
            currentTempFile = tempFile
            
            // Write audio data to temp file
            FileOutputStream(tempFile).use { output ->
                output.write(audioData)
            }
            
            // Initialize MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                
                setDataSource(tempFile.absolutePath)
                prepareAsync()
                
                setOnPreparedListener { player ->
                    Log.i(TAG, "MediaPlayer prepared, starting playback")
                    player.start()
                    isPlaying = true
                    
                    CoroutineScope(Dispatchers.Main).launch {
                        onPlaybackStarted?.invoke()
                    }
                    
                    // Start progress monitoring
                    startProgressMonitoring(player)
                }
                
                setOnCompletionListener { player ->
                    Log.i(TAG, "Playback completed for message: $messageId")
                    isPlaying = false
                    
                    CoroutineScope(Dispatchers.Main).launch {
                        onPlaybackCompleted?.invoke()
                    }
                    
                    // Cleanup immediately after completion (ephemeral)
                    cleanup()
                }
                
                setOnErrorListener { player, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    isPlaying = false
                    
                    CoroutineScope(Dispatchers.Main).launch {
                        onPlaybackError?.invoke("Playback error: $what")
                    }
                    
                    cleanup()
                    true
                }
            }
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
            cleanup()
            withContext(Dispatchers.Main) {
                onPlaybackError?.invoke("Failed to start playback: ${e.message}")
            }
            false
        }
    }
    
    /**
     * Stop current playback
     */
    suspend fun stopPlayback() = withContext(Dispatchers.IO) {
        try {
            if (isPlaying) {
                Log.i(TAG, "Stopping playback")
                isPlaying = false
                
                mediaPlayer?.apply {
                    if (isPlaying) {
                        stop()
                    }
                }
                
                cleanup()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping playback", e)
            cleanup()
        }
    }
    
    /**
     * Pause current playback
     */
    suspend fun pausePlayback() = withContext(Dispatchers.IO) {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    Log.i(TAG, "Playback paused")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing playback", e)
        }
    }
    
    /**
     * Resume paused playback
     */
    suspend fun resumePlayback() = withContext(Dispatchers.IO) {
        try {
            mediaPlayer?.let { player ->
                if (!player.isPlaying) {
                    player.start()
                    Log.i(TAG, "Playback resumed")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming playback", e)
        }
    }
    
    /**
     * Check if currently playing
     */
    fun isPlaying(): Boolean = isPlaying
    
    /**
     * Get current playback position (0.0 to 1.0)
     */
    fun getCurrentPosition(): Float {
        return try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.currentPosition.toFloat() / player.duration.toFloat()
                } else 0f
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
    }
    
    /**
     * Get current playback position in milliseconds
     */
    fun getCurrentPositionMs(): Long {
        return try {
            mediaPlayer?.currentPosition?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
    
    /**
     * Get total duration in milliseconds
     */
    fun getDurationMs(): Long {
        return try {
            mediaPlayer?.duration?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
    
    /**
     * Start progress monitoring for UI updates
     */
    private fun startProgressMonitoring(player: MediaPlayer) {
        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            while (isPlaying && player.isPlaying) {
                try {
                    val progress = player.currentPosition.toFloat() / player.duration.toFloat()
                    
                    withContext(Dispatchers.Main) {
                        onPlaybackProgress?.invoke(progress)
                    }
                    
                    delay(50) // Update every 50ms (20 FPS)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in progress monitoring", e)
                    break
                }
            }
        }
    }
    
    /**
     * Create temporary file for audio playback
     */
    private fun createTempAudioFile(messageId: String): File {
        val filename = "${TEMP_FILE_PREFIX}${messageId}_${System.currentTimeMillis()}${TEMP_FILE_SUFFIX}"
        return File(context.cacheDir, filename)
    }
    
    /**
     * Cleanup resources and delete temporary files (ephemeral behavior)
     */
    private fun cleanup() {
        playbackJob?.cancel()
        
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaPlayer", e)
        }
        mediaPlayer = null
        
        // Delete temporary file immediately (ephemeral)
        currentTempFile?.let { file ->
            try {
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "Deleted temporary audio file: ${file.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete temporary file: ${file.name}", e)
            }
        }
        currentTempFile = null
        
        isPlaying = false
    }
    
    /**
     * Release all resources
     */
    fun release() {
        if (isPlaying) {
            CoroutineScope(Dispatchers.IO).launch {
                stopPlayback()
            }
        } else {
            cleanup()
        }
    }
    
    /**
     * Set audio volume (0.0 to 1.0)
     */
    fun setVolume(volume: Float) {
        try {
            val clampedVolume = volume.coerceIn(0f, 1f)
            mediaPlayer?.setVolume(clampedVolume, clampedVolume)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting volume", e)
        }
    }
    
    /**
     * Get system audio volume level
     */
    fun getSystemVolume(): Float {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            currentVolume.toFloat() / maxVolume.toFloat()
        } catch (e: Exception) {
            Log.w(TAG, "Error getting system volume", e)
            1.0f
        }
    }
}

/**
 * Voice playback state for UI
 */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val progress: Float = 0f,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null
) {
    
    /**
     * Get formatted position string
     */
    fun getFormattedPosition(): String {
        val seconds = positionMs / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }
    
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
     * Get formatted remaining time string
     */
    fun getFormattedRemaining(): String {
        val remainingMs = durationMs - positionMs
        val seconds = remainingMs / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("-%d:%02d", minutes, remainingSeconds)
    }
    
    /**
     * Check if playback is active (playing or paused)
     */
    fun isActive(): Boolean = isPlaying || isPaused
    
    /**
     * Check if playback has error
     */
    fun hasError(): Boolean = error != null
} 