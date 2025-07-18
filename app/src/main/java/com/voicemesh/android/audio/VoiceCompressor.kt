package com.voicemesh.android.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.voicemesh.android.model.CompressionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lz4.LZ4Factory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Voice compressor for VoiceMesh - adaptive compression targeting MVP size goals
 * Based on MVP specification: 5s≈20KB, 30s≈120KB, 60s≈240KB
 */
class VoiceCompressor(private val context: Context) {
    
    companion object {
        private const val TAG = "VoiceCompressor"
        
        // Target sizes as per MVP spec
        private const val TARGET_SIZE_5S = 20_000  // 20KB for 5 seconds
        private const val TARGET_SIZE_30S = 120_000 // 120KB for 30 seconds  
        private const val TARGET_SIZE_60S = 240_000 // 240KB for 60 seconds
        
        // Compression thresholds
        private const val ADDITIONAL_COMPRESSION_THRESHOLD = 1.2 // Apply extra compression if 20% over target
        private const val LZ4_COMPRESSION_THRESHOLD = 50_000 // Apply LZ4 if over 50KB
    }
    
    private val lz4Factory = LZ4Factory.fastestInstance()
    private val lz4Compressor = lz4Factory.fastCompressor()
    private val lz4Decompressor = lz4Factory.fastDecompressor()
    
    /**
     * Compress audio file according to MVP specifications
     */
    suspend fun compressAudio(audioFile: File): CompressedAudioResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting compression for: ${audioFile.absolutePath}")
            
            // Get audio metadata
            val metadata = getAudioMetadata(audioFile)
            val originalSize = audioFile.length().toInt()
            val durationMs = metadata.durationMs
            
            Log.i(TAG, "Audio metadata - Duration: ${durationMs}ms, Size: ${originalSize} bytes")
            
            // Determine target size based on duration
            val targetSize = calculateTargetSize(durationMs)
            
            // Read original audio data
            val originalData = audioFile.readBytes()
            
            // Determine compression strategy
            val compressionType = if (originalSize <= targetSize) {
                // Already within target, use original AAC-LC
                CompressionType.AAC_LC
            } else if (originalSize <= LZ4_COMPRESSION_THRESHOLD) {
                // Small enough for additional AAC optimization
                CompressionType.AAC_LC
            } else {
                // Large file, apply LZ4 compression
                CompressionType.AAC_LC // We'll apply LZ4 post-processing
            }
            
            // Apply compression
            val compressedData = when {
                originalSize <= targetSize -> {
                    Log.i(TAG, "Audio already within target size")
                    originalData
                }
                originalSize <= LZ4_COMPRESSION_THRESHOLD -> {
                    Log.i(TAG, "Applying AAC optimization")
                    optimizeAACCompression(originalData, targetSize)
                }
                else -> {
                    Log.i(TAG, "Applying LZ4 compression")
                    applyLZ4Compression(originalData)
                }
            }
            
            val compressionRatio = originalSize.toFloat() / compressedData.size.toFloat()
            
            Log.i(TAG, "Compression complete - Original: ${originalSize} bytes, Compressed: ${compressedData.size} bytes, Ratio: ${"%.2f".format(compressionRatio)}")
            
            CompressedAudioResult(
                compressedData = compressedData,
                originalSize = originalSize,
                compressedSize = compressedData.size,
                compressionType = compressionType,
                compressionRatio = compressionRatio,
                durationMs = durationMs,
                targetSizeAchieved = compressedData.size <= targetSize
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Compression failed", e)
            CompressedAudioResult(
                compressedData = byteArrayOf(),
                originalSize = 0,
                compressedSize = 0,
                compressionType = CompressionType.AAC_LC,
                compressionRatio = 1.0f,
                durationMs = 0L,
                targetSizeAchieved = false,
                error = e.message
            )
        }
    }
    
    /**
     * Decompress audio data
     */
    suspend fun decompressAudio(compressedData: ByteArray, compressionType: CompressionType): DecompressedAudioResult = withContext(Dispatchers.IO) {
        try {
            val decompressedData = when (compressionType) {
                CompressionType.AAC_LC -> {
                    // Check if LZ4 compressed
                    if (isLZ4Compressed(compressedData)) {
                        applyLZ4Decompression(compressedData)
                    } else {
                        compressedData // Already decompressed AAC
                    }
                }
                CompressionType.OPUS -> {
                    // Future: Implement Opus decompression
                    compressedData
                }
                CompressionType.UNCOMPRESSED -> {
                    compressedData
                }
            }
            
            DecompressedAudioResult(
                audioData = decompressedData,
                success = true
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Decompression failed", e)
            DecompressedAudioResult(
                audioData = byteArrayOf(),
                success = false,
                error = e.message
            )
        }
    }
    
    /**
     * Estimate compressed size for given duration
     */
    fun estimateCompressedSize(durationMs: Long): Int {
        val targetSize = calculateTargetSize(durationMs)
        // Add 10% buffer for estimation
        return (targetSize * 1.1).toInt()
    }
    
    /**
     * Check if audio size is within MVP targets
     */
    fun isWithinTargetSize(audioData: ByteArray, durationMs: Long): Boolean {
        val targetSize = calculateTargetSize(durationMs)
        return audioData.size <= targetSize
    }
    
    /**
     * Calculate target size based on duration (MVP specification)
     */
    private fun calculateTargetSize(durationMs: Long): Int {
        val durationSeconds = durationMs / 1000.0
        
        return when {
            durationSeconds <= 5.0 -> TARGET_SIZE_5S
            durationSeconds <= 30.0 -> {
                // Linear interpolation between 5s and 30s targets
                val progress = (durationSeconds - 5.0) / 25.0
                (TARGET_SIZE_5S + progress * (TARGET_SIZE_30S - TARGET_SIZE_5S)).toInt()
            }
            durationSeconds <= 60.0 -> {
                // Linear interpolation between 30s and 60s targets
                val progress = (durationSeconds - 30.0) / 30.0
                (TARGET_SIZE_30S + progress * (TARGET_SIZE_60S - TARGET_SIZE_30S)).toInt()
            }
            else -> {
                // Beyond 60s, use 60s target
                TARGET_SIZE_60S
            }
        }
    }
    
    /**
     * Get audio metadata
     */
    private fun getAudioMetadata(audioFile: File): AudioMetadata {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(audioFile.absolutePath)
            
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            val sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toIntOrNull() ?: 0
            
            retriever.release()
            
            AudioMetadata(duration, bitrate, sampleRate)
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract audio metadata", e)
            AudioMetadata(0L, 0, 0)
        }
    }
    
    /**
     * Optimize AAC compression (placeholder for future advanced AAC processing)
     */
    private fun optimizeAACCompression(audioData: ByteArray, targetSize: Int): ByteArray {
        // For now, return original data as AAC is already optimized
        // In the future, could implement:
        // - Re-encoding with lower bitrate
        // - Advanced AAC parameters tuning
        // - Psychoacoustic model optimization
        
        return if (audioData.size > targetSize * ADDITIONAL_COMPRESSION_THRESHOLD) {
            // Apply simple data reduction if significantly over target
            applyLZ4Compression(audioData)
        } else {
            audioData
        }
    }
    
    /**
     * Apply LZ4 compression
     */
    private fun applyLZ4Compression(audioData: ByteArray): ByteArray {
        return try {
            val compressed = lz4Compressor.compress(audioData)
            
            // Add LZ4 header: magic number + original size + compressed data
            val buffer = ByteBuffer.allocate(4 + 4 + compressed.size)
            buffer.putInt(0x4C5A3401) // LZ4 magic number
            buffer.putInt(audioData.size) // Original size
            buffer.put(compressed)
            
            buffer.array()
        } catch (e: Exception) {
            Log.w(TAG, "LZ4 compression failed, returning original", e)
            audioData
        }
    }
    
    /**
     * Apply LZ4 decompression
     */
    private fun applyLZ4Decompression(compressedData: ByteArray): ByteArray {
        return try {
            val buffer = ByteBuffer.wrap(compressedData)
            val magic = buffer.int
            
            if (magic != 0x4C5A3401) {
                throw IllegalArgumentException("Invalid LZ4 magic number")
            }
            
            val originalSize = buffer.int
            val compressedPart = ByteArray(buffer.remaining())
            buffer.get(compressedPart)
            
            lz4Decompressor.decompress(compressedPart, originalSize)
        } catch (e: Exception) {
            Log.w(TAG, "LZ4 decompression failed, returning original", e)
            compressedData
        }
    }
    
    /**
     * Check if data is LZ4 compressed
     */
    private fun isLZ4Compressed(data: ByteArray): Boolean {
        return data.size >= 8 && ByteBuffer.wrap(data).int == 0x4C5A3401
    }
}

/**
 * Audio metadata container
 */
data class AudioMetadata(
    val durationMs: Long,
    val bitrate: Int,
    val sampleRate: Int
)

/**
 * Compressed audio result
 */
data class CompressedAudioResult(
    val compressedData: ByteArray,
    val originalSize: Int,
    val compressedSize: Int,
    val compressionType: CompressionType,
    val compressionRatio: Float,
    val durationMs: Long,
    val targetSizeAchieved: Boolean,
    val error: String? = null
) {
    
    /**
     * Get compression efficiency description
     */
    fun getCompressionDescription(): String {
        return when {
            error != null -> "Compression failed: $error"
            targetSizeAchieved -> "Target size achieved (${compressionRatio.format(2)}x compression)"
            compressionRatio > 1.0f -> "Compressed ${compressionRatio.format(2)}x (${compressedSize} bytes)"
            else -> "No compression applied (${compressedSize} bytes)"
        }
    }
    
    /**
     * Check if compression was successful
     */
    fun isSuccessful(): Boolean = error == null && compressedData.isNotEmpty()
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as CompressedAudioResult
        
        return compressedData.contentEquals(other.compressedData) &&
                originalSize == other.originalSize &&
                compressedSize == other.compressedSize &&
                compressionType == other.compressionType &&
                compressionRatio == other.compressionRatio &&
                durationMs == other.durationMs &&
                targetSizeAchieved == other.targetSizeAchieved &&
                error == other.error
    }
    
    override fun hashCode(): Int {
        var result = compressedData.contentHashCode()
        result = 31 * result + originalSize
        result = 31 * result + compressedSize
        result = 31 * result + compressionType.hashCode()
        result = 31 * result + compressionRatio.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + targetSizeAchieved.hashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}

/**
 * Decompressed audio result
 */
data class DecompressedAudioResult(
    val audioData: ByteArray,
    val success: Boolean,
    val error: String? = null
) {
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as DecompressedAudioResult
        
        return audioData.contentEquals(other.audioData) &&
                success == other.success &&
                error == other.error
    }
    
    override fun hashCode(): Int {
        var result = audioData.contentHashCode()
        result = 31 * result + success.hashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}

/**
 * Extension function for Float formatting
 */
private fun Float.format(digits: Int) = "%.${digits}f".format(this) 