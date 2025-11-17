package com.cortexn.cortexn

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * CortexN Reflex - Spiking Neural Network runtime for gesture recognition
 * 
 * Implements event-driven inference with:
 * - LIF (Leaky Integrate-and-Fire) neurons
 * - Rate/latency spike encoding
 * - NEON/I8MM/SME2 optimized kernels
 * - Sub-millisecond latency for real-time control
 */
class CortexNReflex(private val context: Context) {
    
    private var layerHandle: Long = 0
    private var isInitialized = false
    
    companion object {
        init {
            System.loadLibrary("cortexn_snn")
            Timber.plant(Timber.DebugTree())
        }
        
        private const val DEFAULT_INPUT_SIZE = 6  // 3-axis accel + 3-axis gyro
        private const val DEFAULT_OUTPUT_SIZE = 6 // 6 gesture classes
        private const val DEFAULT_TAU_MEM = 10.0f
        private const val DEFAULT_V_THRESH = 1.0f
    }
    
    /**
     * Initializes the SNN layer with specified parameters
     */
    fun initialize(
        inputSize: Int = DEFAULT_INPUT_SIZE,
        outputSize: Int = DEFAULT_OUTPUT_SIZE,
        tauMem: Float = DEFAULT_TAU_MEM,
        vThresh: Float = DEFAULT_V_THRESH
    ) {
        if (isInitialized) {
            Timber.w("Already initialized, releasing previous layer")
            release()
        }
        
        Timber.i("Initializing CortexN SNN: input=$inputSize, output=$outputSize")
        
        layerHandle = nativeCreateLayer(inputSize, outputSize, tauMem, vThresh)
        
        if (layerHandle == 0L) {
            throw RuntimeException("Failed to create native SNN layer")
        }
        
        isInitialized = true
        Timber.i("✓ CortexN SNN initialized (handle: $layerHandle)")
        
        // Load pre-trained weights if available
        loadWeights()
    }
    
    /**
     * Performs forward inference on input spikes
     * @param inputSpikes Input spike array [input_size]
     * @return Output spike array [output_size]
     */
    fun forward(inputSpikes: FloatArray): FloatArray {
        if (!isInitialized) {
            throw IllegalStateException("SNN not initialized. Call initialize() first")
        }
        
        val outputSpikes = FloatArray(DEFAULT_OUTPUT_SIZE)
        nativeForward(layerHandle, inputSpikes, outputSpikes)
        
        return outputSpikes
    }
    
    /**
     * Resets neuron membrane potentials
     */
    fun reset() {
        if (!isInitialized) {
            return
        }
        
        nativeReset(layerHandle)
        Timber.d("Neuron states reset")
    }
    
    /**
     * Releases native resources
     */
    fun release() {
        if (isInitialized) {
            nativeRelease(layerHandle)
            layerHandle = 0
            isInitialized = false
            Timber.i("CortexN SNN released")
        }
    }
    
    /**
     * Loads pre-trained weights from assets
     */
    private fun loadWeights() {
        try {
            val weightsPath = "models/lif_gesture_weights.bin"
            val weightsFile = copyAssetToCache(weightsPath)
            
            // Load weights via JNI
            nativeLoadWeights(layerHandle, weightsFile.absolutePath)
            
            Timber.i("✓ Weights loaded from $weightsPath")
        } catch (e: Exception) {
            Timber.w(e, "Failed to load weights, using random initialization")
        }
    }
    
    /**
     * Copies asset file to cache directory
     */
    private fun copyAssetToCache(assetPath: String): File {
        val cacheFile = File(context.cacheDir, assetPath.substringAfterLast('/'))
        
        if (!cacheFile.exists()) {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        
        return cacheFile
    }
    
    /**
     * Runs kernel benchmarks
     */
    fun benchmark(batchSize: Int = 1, inputSize: Int = 128, outputSize: Int = 64) {
        Timber.i("Running SNN kernel benchmarks...")
        nativeBenchmark(batchSize, inputSize, outputSize)
    }
    
    /**
     * Gets hardware capabilities
     */
    fun getHardwareInfo(): HardwareInfo {
        return HardwareInfo(
            hasNeon = nativeHasNEON(),
            hasI8MM = nativeHasI8MM(),
            hasSME2 = nativeHasSME2(),
            preferredKernel = nativeGetPreferredKernel()
        )
    }
    
    data class HardwareInfo(
        val hasNeon: Boolean,
        val hasI8MM: Boolean,
        val hasSME2: Boolean,
        val preferredKernel: String
    )
    
    // Native methods
    private external fun nativeCreateLayer(
        inputSize: Int,
        outputSize: Int,
        tauMem: Float,
        vThresh: Float
    ): Long
    
    private external fun nativeForward(
        layerHandle: Long,
        inputSpikes: FloatArray,
        outputSpikes: FloatArray
    )
    
    private external fun nativeReset(layerHandle: Long)
    
    private external fun nativeRelease(layerHandle: Long)
    
    private external fun nativeLoadWeights(layerHandle: Long, weightsPath: String)
    
    private external fun nativeBenchmark(batchSize: Int, inputSize: Int, outputSize: Int)
    
    private external fun nativeHasNEON(): Boolean
    private external fun nativeHasI8MM(): Boolean
    private external fun nativeHasSME2(): Boolean
    private external fun nativeGetPreferredKernel(): String
}

/**
 * Example usage
 */
fun main() {
    // Initialize SNN
    val snn = CortexNReflex(context)
    snn.initialize(
        inputSize = 6,     // IMU: 3-axis accel + 3-axis gyro
        outputSize = 6,    // 6 gesture classes
        tauMem = 10.0f,
        vThresh = 1.0f
    )
    
    // Print hardware capabilities
    val hwInfo = snn.getHardwareInfo()
    Timber.i("Hardware: NEON=${hwInfo.hasNeon}, I8MM=${hwInfo.hasI8MM}, SME2=${hwInfo.hasSME2}")
    Timber.i("Preferred kernel: ${hwInfo.preferredKernel}")
    
    // Run inference
    val imuData = floatArrayOf(0.5f, -0.2f, 0.8f, 0.1f, 0.0f, -0.3f)
    val spikes = snn.forward(imuData)
    
    // Decode gesture (argmax)
    val gesture = spikes.indices.maxByOrNull { spikes[it] } ?: -1
    Timber.i("Detected gesture: $gesture (spike count: ${spikes.sum()})")
    
    // Run benchmark
    snn.benchmark(batchSize = 1, inputSize = 128, outputSize = 64)
    
    // Cleanup
    snn.release()
}
