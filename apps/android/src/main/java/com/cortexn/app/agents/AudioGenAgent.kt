package com.cortexn.app.agents

import android.content.Context
import android.content.Intent
import com.cortexn.app.policy.PromptSchemas
import com.cortexn.app.services.AudioGenService
import com.cortexn.audiogen.AudioGen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * AudioGen Agent - Text-to-Audio Generation
 * 
 * Wraps AudioGenService for music/soundscape generation
 */
class AudioGenAgent(private val context: Context) {
    
    private var audioGen: AudioGen? = null
    private var isInitialized = false
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.i("Initializing AudioGen Agent...")
            
            audioGen = AudioGen(context)
            
            val config = AudioGen.Config(
                modelDir = audioGen!!.getDefaultModelDir(),
                useGpu = false,
                numThreads = 4
            )
            
            isInitialized = audioGen!!.initialize(config)
            
            if (isInitialized) {
                Timber.i("✓ AudioGen Agent initialized")
            }
            
            isInitialized
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize AudioGen Agent")
            false
        }
    }
    
    suspend fun generate(prompt: PromptSchemas.AudioGenPrompt): GenerationResult = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            throw IllegalStateException("AudioGen Agent not initialized")
        }
        
        val startTime = System.currentTimeMillis()
        
        val params = AudioGen.GenerationParams(
            prompt = prompt.text,
            durationSeconds = prompt.duration,
            numInferenceSteps = 50,
            guidanceScale = 3.0f
        )
        
        val audio = audioGen!!.generate(params)
        
        val latency = System.currentTimeMillis() - startTime
        
        GenerationResult(
            audioData = audio ?: floatArrayOf(),
            durationSeconds = prompt.duration,
            latencyMs = latency,
            success = audio != null
        )
    }
    
    fun startService(prompt: PromptSchemas.AudioGenPrompt) {
        val intent = Intent(context, AudioGenService::class.java).apply {
            putExtra("prompt", prompt.text)
            putExtra("duration", prompt.duration)
        }
        context.startService(intent)
    }
    
    fun shutdown() {
        if (isInitialized) {
            audioGen?.release()
            audioGen = null
            isInitialized = false
        }
    }
    
    data class GenerationResult(
        val audioData: FloatArray,
        val durationSeconds: Float,
        val latencyMs: Long,
        val success: Boolean
    )
}
