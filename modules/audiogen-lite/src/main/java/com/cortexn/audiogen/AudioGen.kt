package com.cortexn.audiogen

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * AudioGen - Text-to-audio generation using Stable Audio Open Small
 * 
 * High-level API for generating audio from text prompts
 * Uses TensorFlow Lite with XNNPACK/GPU acceleration
 */
class AudioGen(private val context: Context) {
    
    private var enginePtr: Long = 0
    private var isInitialized = false
    
    companion object {
        init {
            System.loadLibrary("audiogen_lite")
            Timber.plant(Timber.DebugTree())
        }
        
        private const val DEFAULT_SAMPLE_RATE = 16000
        private const val DEFAULT_NUM_STEPS = 50
        private const val DEFAULT_GUIDANCE_SCALE = 3.0f
    }
    
    /**
     * Configuration for AudioGen engine
     */
    data class Config(
        val modelDir: String,
        val useGpu: Boolean = false,
        val numThreads: Int = 4
    )
    
    /**
     * Parameters for audio generation
     */
    data class GenerationParams(
        val prompt: String,
        val durationSeconds: Float = 10.0f,
        val numInferenceSteps: Int = DEFAULT_NUM_STEPS,
        val guidanceScale: Float = DEFAULT_GUIDANCE_SCALE,
        val seed: Int = -1
    )
    
    /**
     * Progress callback interface
     */
    fun interface ProgressCallback {
        fun onProgress(currentStep: Int, totalSteps: Int, status: String)
    }
    
    /**
     * Initialize AudioGen with configuration
     */
    fun initialize(config: Config): Boolean {
        if (isInitialized) {
            Timber.w("Already initialized, releasing previous engine")
            release()
        }
        
        Timber.i("Initializing AudioGen: dir=${config.modelDir}, gpu=${config.useGpu}")
        
        // Verify model files exist
        val modelDir = File(config.modelDir)
        if (!modelDir.exists() || !modelDir.isDirectory) {
            Timber.e("Model directory not found: ${config.modelDir}")
            return false
        }
        
        val requiredFiles = listOf(
            "conditioners.tflite",
            "dit_int8_dynamic.tflite",
            "autoencoder_fp16.tflite"
        )
        
        for (file in requiredFiles) {
            if (!File(modelDir, file).exists()) {
                Timber.e("Missing model file: $file")
                return false
            }
        }
        
        // Create native engine
        enginePtr = nativeCreate(config.modelDir, config.useGpu, config.numThreads)
        
        if (enginePtr == 0L) {
            Timber.e("Failed to create native engine")
            return false
        }
        
        // Initialize models
        if (!nativeInitialize(enginePtr)) {
            Timber.e("Failed to initialize models")
            nativeDestroy(enginePtr)
            enginePtr = 0
            return false
        }
        
        isInitialized = true
        Timber.i("✓ AudioGen initialized")
        return true
    }
    
    /**
     * Generate audio from text prompt
     */
    fun generate(params: GenerationParams): FloatArray? {
        if (!isInitialized) {
            Timber.e("AudioGen not initialized")
            return null
        }
        
        Timber.i("Generating audio: '${params.prompt}' (${params.durationSeconds}s)")
        
        val audio = nativeGenerate(
            enginePtr,
            params.prompt,
            params.durationSeconds,
            params.numInferenceSteps,
            params.guidanceScale
        )
        
        if (audio != null) {
            Timber.i("✓ Generated ${audio.size} samples (${audio.size / DEFAULT_SAMPLE_RATE.toFloat()}s)")
        } else {
            Timber.e("Generation failed")
        }
        
        return audio
    }
    
    /**
     * Generate audio with progress updates
     */
    fun generateWithProgress(
        params: GenerationParams,
        callback: ProgressCallback
    ): FloatArray? {
        if (!isInitialized) {
            Timber.e("AudioGen not initialized")
            return null
        }
        
        Timber.i("Generating audio with progress: '${params.prompt}'")
        
        return nativeGenerateWithProgress(
            enginePtr,
            params.prompt,
            params.durationSeconds,
            params.numInferenceSteps,
            params.guidanceScale,
            callback
        )
    }
    
    /**
     * Cancel ongoing generation
     */
    fun cancel() {
        if (isInitialized) {
            nativeCancel(enginePtr)
            Timber.i("Generation cancelled")
        }
    }
    
    /**
     * Release resources
     */
    fun release() {
        if (isInitialized) {
            nativeDestroy(enginePtr)
            enginePtr = 0
            isInitialized = false
            Timber.i("AudioGen released")
        }
    }
    
    /**
     * Get default model directory from assets
     */
    fun getDefaultModelDir(): String {
        val cacheDir = File(context.cacheDir, "audiogen_models")
        cacheDir.mkdirs()
        
        // Copy models from assets if needed
        copyModelsFromAssets(cacheDir)
        
        return cacheDir.absolutePath
    }
    
    private fun copyModelsFromAssets(targetDir: File) {
        val modelFiles = listOf(
            "conditioners.tflite",
            "dit_int8_dynamic.tflite",
            "autoencoder_fp16.tflite"
        )
        
        for (filename in modelFiles) {
            val targetFile = File(targetDir, filename)
            
            if (!targetFile.exists()) {
                try {
                    context.assets.open("models/$filename").use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Timber.d("Copied $filename to cache")
                } catch (e: Exception) {
                    Timber.w(e, "Failed to copy $filename")
                }
            }
        }
    }
    
    // Native methods
    private external fun nativeCreate(
        modelDir: String,
        useGpu: Boolean,
        numThreads: Int
    ): Long
    
    private external fun nativeInitialize(enginePtr: Long): Boolean
    
    private external fun nativeGenerate(
        enginePtr: Long,
        prompt: String,
        duration: Float,
        numSteps: Int,
        guidanceScale: Float
    ): FloatArray?
    
    private external fun nativeGenerateWithProgress(
        enginePtr: Long,
        prompt: String,
        duration: Float,
        numSteps: Int,
        guidanceScale: Float,
        callback: ProgressCallback
    ): FloatArray?
    
    private external fun nativeCancel(enginePtr: Long)
    
    private external fun nativeDestroy(enginePtr: Long)
    
    // Ring buffer native methods
    external fun nativeCreateRingBuffer(capacity: Int): Long
    external fun nativeRingBufferWrite(bufferPtr: Long, data: FloatArray, count: Int): Int
    external fun nativeRingBufferRead(bufferPtr: Long, data: FloatArray, count: Int): Int
    external fun nativeRingBufferAvailable(bufferPtr: Long): Int
    external fun nativeRingBufferClear(bufferPtr: Long)
    external fun nativeDestroyRingBuffer(bufferPtr: Long)
}
