package com.cortexn.app.agents

import android.content.Context
import com.cortexn.agents.asr.WhisperRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * ASR Agent - Speech Recognition
 * 
 * Wraps WhisperRunner with ExecuTorch
 * Provides high-level interface for:
 * - Real-time transcription
 * - Voice commands
 * - Audio note-taking
 */
class AsrAgent(private val context: Context) {
    
    private lateinit var whisperRunner: WhisperRunner
    private var isInitialized = false
    
    /**
     * Initialize Whisper model
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.i("Initializing ASR Agent (Whisper-tiny)...")
            
            whisperRunner = WhisperRunner(
                context = context,
                modelPath = "models/whisper-tiny.pte",
                config = WhisperRunner.WhisperConfig(
                    sampleRate = 16000,
                    language = "en",
                    task = WhisperRunner.WhisperConfig.Task.TRANSCRIBE
                )
            )
            
            isInitialized = whisperRunner.initialize()
            
            if (isInitialized) {
                Timber.i("✓ ASR Agent initialized")
            }
            
            isInitialized
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize ASR Agent")
            false
        }
    }
    
    /**
     * Transcribe audio file
     */
    suspend fun transcribe(audioFilePath: String): TranscriptionResult = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            throw IllegalStateException("ASR Agent not initialized")
        }
        
        val startTime = System.currentTimeMillis()
        
        val text = whisperRunner.transcribeFile(audioFilePath)
        
        val latency = System.currentTimeMillis() - startTime
        
        TranscriptionResult(
            text = text,
            latencyMs = latency,
            language = "en"
        )
    }
    
    /**
     * Transcribe audio buffer
     */
    suspend fun transcribeBuffer(audioData: FloatArray): TranscriptionResult = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            throw IllegalStateException("ASR Agent not initialized")
        }
        
        val startTime = System.currentTimeMillis()
        
        val text = whisperRunner.transcribe(audioData)
        
        val latency = System.currentTimeMillis() - startTime
        
        TranscriptionResult(
            text = text,
            latencyMs = latency,
            language = "en"
        )
    }
    
    fun shutdown() {
        if (isInitialized) {
            whisperRunner.release()
            isInitialized = false
        }
    }
    
    data class TranscriptionResult(
        val text: String,
        val latencyMs: Long,
        val language: String
    )
}
