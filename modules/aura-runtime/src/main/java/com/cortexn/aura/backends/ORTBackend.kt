package com.cortexn.aura.backends

import android.content.Context
import ai.onnxruntime.*
import com.cortexn.aura.ExecutionMetrics
import com.cortexn.aura.TaskSpec
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer

/**
 * ONNX Runtime backend with XNNPACK EP (KleidiAI optimized)
 * 
 * Optimizations:
 * - XNNPACK execution provider with ARM NEON/I8MM/SME2 kernels
 * - Graph optimizations (constant folding, operator fusion)
 * - Dynamic quantization for INT8 inference
 * - Multi-threaded inference with optimal thread count
 */
class ORTBackend(private val context: Context) : InferenceBackend {
    
    private val sessions = mutableMapOf<String, OrtSession>()
    private lateinit var env: OrtEnvironment
    
    // XNNPACK execution provider options
    private val xnnpackOptions = mapOf(
        "intra_op_num_threads" to getOptimalThreadCount().toString(),
        "inter_op_num_threads" to "1"
    )
    
    init {
        initializeORT()
    }
    
    private fun initializeORT() {
        Timber.i("Initializing ONNX Runtime with XNNPACK...")
        
        env = OrtEnvironment.getEnvironment(
            OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING,
            "CortexN-ORT"
        )
        
        Timber.i("✓ ONNX Runtime ${OrtEnvironment.getVersion()}")
    }
    
    override suspend fun infer(spec: TaskSpec): List<FloatArray> {
        val session = getOrCreateSession(spec)
        
        // Prepare inputs
        val inputs = prepareInputs(spec, session)
        
        // Run inference
        val startTime = System.nanoTime()
        val outputs = session.run(inputs)
        val inferenceTime = (System.nanoTime() - startTime) / 1_000_000f
        
        Timber.d("ORT inference completed: ${inferenceTime}ms")
        
        // Extract output tensors
        val results = outputs.map { (_, value) ->
            when (value) {
                is OnnxTensor -> {
                    val buffer = value.floatBuffer
                    FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
                else -> floatArrayOf()
            }
        }
        
        // Cleanup
        outputs.forEach { (_, value) -> value.close() }
        inputs.forEach { (_, value) -> value.close() }
        
        return results
    }
    
    override suspend fun warmup(spec: TaskSpec) {
        Timber.d("Warming up ORT backend for ${spec.modelId}")
        repeat(3) {
            infer(spec)
        }
    }
    
    private fun getOrCreateSession(spec: TaskSpec): OrtSession {
        return sessions.getOrPut(spec.modelId) {
            createSession(spec)
        }
    }
    
    private fun createSession(spec: TaskSpec): OrtSession {
        Timber.i("Creating ORT session for ${spec.modelId}")
        
        val modelPath = resolveModelPath(spec.modelId)
        
        // Session options with optimizations
        val sessionOptions = OrtSession.SessionOptions().apply {
            // Graph optimization level
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            
            // Enable XNNPACK EP for ARM optimization
            addXNNPACK(xnnpackOptions)
            
            // Thread configuration
            setIntraOpNumThreads(getOptimalThreadCount())
            setInterOpNumThreads(1)
            
            // Memory optimization
            setMemoryPatternOptimization(true)
            setCPUArenaAllocator(true)
            
            // Graph optimization
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            
            // Enable profiling for debugging (disable in production)
            if (BuildConfig.DEBUG) {
                setProfileFilePrefix("ort_profile_${spec.modelId}")
            }
        }
        
        return env.createSession(modelPath, sessionOptions)
    }
    
    private fun prepareInputs(
        spec: TaskSpec,
        session: OrtSession
    ): Map<String, OnnxTensor> {
        val inputInfo = session.inputInfo
        val inputs = mutableMapOf<String, OnnxTensor>()
        
        inputInfo.entries.forEachIndexed { index, (name, info) ->
            val shape = spec.inputShapes.getOrNull(index)
                ?: throw IllegalArgumentException("Missing input shape for input $index")
            
            // Create dummy input tensor (in production, use actual data)
            val tensor = createDummyTensor(shape, info.info as TensorInfo)
            inputs[name] = tensor
        }
        
        return inputs
    }
    
    private fun createDummyTensor(shape: IntArray, info: TensorInfo): OnnxTensor {
        val totalSize = shape.fold(1) { acc, dim -> acc * dim }
        
        return when (info.type) {
            OnnxJavaType.FLOAT -> {
                val buffer = FloatBuffer.allocate(totalSize)
                // Fill with random values
                repeat(totalSize) { buffer.put(it, (Math.random() * 2 - 1).toFloat()) }
                OnnxTensor.createTensor(env, buffer, shape.map { it.toLong() }.toLongArray())
            }
            OnnxJavaType.INT8, OnnxJavaType.UINT8 -> {
                val buffer = ByteBuffer.allocate(totalSize)
                repeat(totalSize) { buffer.put(it, (Math.random() * 255).toByte()) }
                OnnxTensor.createTensor(env, buffer, shape.map { it.toLong() }.toLongArray())
            }
            OnnxJavaType.INT64 -> {
                val buffer = LongBuffer.allocate(totalSize)
                repeat(totalSize) { buffer.put(it, (Math.random() * 100).toLong()) }
                OnnxTensor.createTensor(env, buffer, shape.map { it.toLong() }.toLongArray())
            }
            else -> throw UnsupportedOperationException("Unsupported tensor type: ${info.type}")
        }
    }
    
    private fun resolveModelPath(modelId: String): String {
        // Check assets
        val assetsPath = "models/$modelId"
        if (context.assets.list("models")?.contains(modelId) == true) {
            // Copy to cache for ORT
            val cacheFile = File(context.cacheDir, modelId)
            if (!cacheFile.exists()) {
                context.assets.open(assetsPath).use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            return cacheFile.absolutePath
        }
        
        // Check file system
        val file = File(modelId)
        if (file.exists()) {
            return file.absolutePath
        }
        
        throw IllegalArgumentException("Model not found: $modelId")
    }
    
    private fun getOptimalThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        // Use 75% of available cores, minimum 1
        return maxOf(1, (cores * 0.75).toInt())
    }
    
    override fun release() {
        Timber.i("Releasing ORT backend resources")
        sessions.values.forEach { it.close() }
        sessions.clear()
        env.close()
    }
}

/**
 * Extension to add XNNPACK execution provider
 */
private fun OrtSession.SessionOptions.addXNNPACK(options: Map<String, String>) {
    try {
        // XNNPACK provider with options
        val providerOptions = mutableMapOf<String, String>()
        providerOptions.putAll(options)
        
        addExecutionProvider("XNNPACK", providerOptions)
        Timber.i("✓ XNNPACK execution provider enabled")
    } catch (e: Exception) {
        Timber.w(e, "Failed to enable XNNPACK, falling back to CPU")
    }
}

private typealias ByteBuffer = java.nio.ByteBuffer
private typealias LongBuffer = java.nio.LongBuffer
