package com.voicemesh.android.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Voice recorder for VoiceMesh - AAC-LC recording with real-time level monitoring
 * Based on MVP specification: 16kHz, 32kbps, mono, 60s max duration
 */
class VoiceRecorder(private val context: Context) {
    
    companion object {
        private const val TAG = "VoiceRecorder"
        
        // Audio configuration as per MVP spec
        private const val SAMPLE_RATE = 16000 // 16kHz for voice
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val TARGET_BITRATE = 32000 // 32kbps
        private const val MAX_DURATION_MS = 60000 // 60 seconds max
        
        // Level monitoring
        private const val LEVEL_UPDATE_INTERVAL_MS = 50L // 20 FPS
        private const val SILENCE_THRESHOLD = 0.01f // Minimum level to consider as voice
    }
    
    private var mediaRecorder: MediaRecorder? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var levelMonitoringJob: Job? = null
    private var currentOutputFile: File? = null
    private var recordingStartTime = 0L
    
    // Audio level callback
    var onLevelUpdate: ((Float) -> Unit)? = null
    var onRecordingComplete: ((File?, Long) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    // Audio level monitoring using AudioRecord for real-time feedback
    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    private val audioBuffer = ShortArray(bufferSize)
    
    /**
     * Start recording voice message
     */
    suspend fun startRecording(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isRecording) {
                Log.w(TAG, "Already recording")
                return@withContext false
            }
            
            // Create temporary file for recording
            val outputFile = createTempAudioFile()
            currentOutputFile = outputFile
            
            // Initialize MediaRecorder for AAC-LC encoding
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(TARGET_BITRATE)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioChannels(1) // Mono
                setMaxDuration(MAX_DURATION_MS)
                setOutputFile(outputFile.absolutePath)
                
                setOnInfoListener { _, what, _ ->
                    when (what) {
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> {
                            Log.i(TAG, "Max duration reached, stopping recording")
                            CoroutineScope(Dispatchers.IO).launch {
                                stopRecording()
                            }
                        }
                    }
                }
                
                prepare()
                start()
            }
            
            // Initialize AudioRecord for level monitoring
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            ).apply {
                startRecording()
            }
            
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            
            // Start level monitoring
            startLevelMonitoring()
            
            Log.i(TAG, "Recording started: ${outputFile.absolutePath}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cleanup()
            onError?.invoke("Failed to start recording: ${e.message}")
            false
        }
    }
    
    /**
     * Stop recording and return the audio file
     */
    suspend fun stopRecording(): File? = withContext(Dispatchers.IO) {
        try {
            if (!isRecording) {
                Log.w(TAG, "Not currently recording")
                return@withContext null
            }
            
            isRecording = false
            val duration = System.currentTimeMillis() - recordingStartTime
            
            // Stop level monitoring
            levelMonitoringJob?.cancel()
            
            // Stop AudioRecord
            audioRecord?.apply {
                stop()
                release()
            }
            audioRecord = null
            
            // Stop MediaRecorder
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            
            val outputFile = currentOutputFile
            currentOutputFile = null
            
            if (outputFile != null && outputFile.exists() && outputFile.length() > 0) {
                Log.i(TAG, "Recording completed: ${outputFile.absolutePath}, duration: ${duration}ms, size: ${outputFile.length()} bytes")
                
                // Validate audio file
                if (validateAudioFile(outputFile)) {
                    onRecordingComplete?.invoke(outputFile, duration)
                    outputFile
                } else {
                    Log.e(TAG, "Invalid audio file produced")
                    outputFile.delete()
                    onError?.invoke("Invalid audio file produced")
                    null
                }
            } else {
                Log.e(TAG, "No valid audio file produced")
                onError?.invoke("No audio recorded")
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            cleanup()
            onError?.invoke("Failed to stop recording: ${e.message}")
            null
        }
    }
    
    /**
     * Cancel current recording
     */
    suspend fun cancelRecording() = withContext(Dispatchers.IO) {
        try {
            if (isRecording) {
                isRecording = false
                
                levelMonitoringJob?.cancel()
                
                mediaRecorder?.apply {
                    stop()
                    release()
                }
                mediaRecorder = null
                
                audioRecord?.apply {
                    stop()
                    release()
                }
                audioRecord = null
                
                // Delete the temp file
                currentOutputFile?.delete()
                currentOutputFile = null
                
                Log.i(TAG, "Recording cancelled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling recording", e)
        } finally {
            cleanup()
        }
    }
    
    /**
     * Check if currently recording
     */
    fun isRecording(): Boolean = isRecording
    
    /**
     * Get current recording duration in milliseconds
     */
    fun getCurrentDurationMs(): Long {
        return if (isRecording) {
            System.currentTimeMillis() - recordingStartTime
        } else 0L
    }
    
    /**
     * Get remaining recording time in milliseconds
     */
    fun getRemainingDurationMs(): Long {
        return if (isRecording) {
            maxOf(0, MAX_DURATION_MS - getCurrentDurationMs())
        } else MAX_DURATION_MS.toLong()
    }
    
    /**
     * Start real-time audio level monitoring
     */
    private fun startLevelMonitoring() {
        levelMonitoringJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                try {
                    val readSize = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    
                    if (readSize > 0) {
                        val level = calculateAudioLevel(audioBuffer, readSize)
                        withContext(Dispatchers.Main) {
                            onLevelUpdate?.invoke(level)
                        }
                    }
                    
                    delay(LEVEL_UPDATE_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in level monitoring", e)
                    break
                }
            }
        }
    }
    
    /**
     * Calculate audio level from PCM samples (0.0 to 1.0)
     */
    private fun calculateAudioLevel(buffer: ShortArray, readSize: Int): Float {
        var sum = 0.0
        for (i in 0 until readSize) {
            sum += (buffer[i] * buffer[i]).toDouble()
        }
        
        val rms = sqrt(sum / readSize)
        
        // Convert RMS to dB and normalize to 0.0-1.0 range
        val db = 20 * log10(rms / Short.MAX_VALUE + 1e-10)
        val normalizedLevel = (db + 60) / 60 // Assuming -60dB as silence threshold
        
        return maxOf(0f, minOf(1f, normalizedLevel.toFloat()))
    }
    
    /**
     * Create temporary file for audio recording
     */
    private fun createTempAudioFile(): File {
        val timestamp = System.currentTimeMillis()
        val filename = "voice_${timestamp}.m4a"
        return File(context.cacheDir, filename)
    }
    
    /**
     * Validate recorded audio file
     */
    private fun validateAudioFile(file: File): Boolean {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            
            retriever.release()
            
            // Valid if has audio track and reasonable duration
            hasAudio == "yes" && duration != null && duration > 100 // At least 100ms
            
        } catch (e: Exception) {
            Log.w(TAG, "Could not validate audio file", e)
            false
        }
    }
    
    /**
     * Cleanup resources
     */
    private fun cleanup() {
        recordingJob?.cancel()
        levelMonitoringJob?.cancel()
        
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaRecorder", e)
        }
        mediaRecorder = null
        
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
        
        isRecording = false
        currentOutputFile = null
    }
    
    /**
     * Release all resources
     */
    fun release() {
        if (isRecording) {
            CoroutineScope(Dispatchers.IO).launch {
                cancelRecording()
            }
        } else {
            cleanup()
        }
    }
}

/**
 * Audio recording state for UI
 */
data class RecordingState(
    val isRecording: Boolean = false,
    val currentLevel: Float = 0f,
    val durationMs: Long = 0L,
    val remainingMs: Long = 60000L,
    val error: String? = null
) {
    
    /**
     * Get progress as percentage (0.0 to 1.0)
     */
    fun getProgress(): Float {
        return durationMs.toFloat() / 60000f
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
        val seconds = remainingMs / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }
    
    /**
     * Check if recording is approaching max duration
     */
    fun isNearMaxDuration(): Boolean {
        return remainingMs < 10000 // Less than 10 seconds remaining
    }
}

 