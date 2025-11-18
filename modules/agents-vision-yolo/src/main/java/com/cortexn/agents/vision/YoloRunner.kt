package com.cortexn.agents.vision

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * YOLOv8 Runner with ONNX Runtime
 * 
 * Features:
 * - XNNPACK execution provider with KleidiAI optimizations
 * - Optimized preprocessing (letterbox + normalization)
 * - NMS (Non-Maximum Suppression) postprocessing
 * - Multi-threaded inference
 * - Hardware acceleration (CPU/GPU/NNAPI)
 */
class YoloRunner(
    private val context: Context,
    private val modelPath: String,
    private val config: YoloConfig = YoloConfig()
) {
    
    private lateinit var ortEnv: OrtEnvironment
    private lateinit var ortSession: OrtSession
    private var isInitialized = false
    
    private val prePost = YoloPrePost(config)
    
    data class YoloConfig(
        val inputWidth: Int = 640,
        val inputHeight: Int = 640,
        val confidenceThreshold: Float = 0.25f,
        val iouThreshold: Float = 0.45f,
        val maxDetections: Int = 100,
        val numClasses: Int = 80,  // COCO dataset classes
        val useGpu: Boolean = false,
        val numThreads: Int = 4,
        val labels: List<String> = COCO_LABELS
    ) {
        companion object {
            val COCO_LABELS = listOf(
                "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
                "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
                "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
                "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
                "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
                "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
                "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
                "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
                "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
                "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
                "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
                "toothbrush"
            )
        }
    }
    
    /**
     * Detection result
     */
    data class Detection(
        val bbox: RectF,           // Bounding box (x1, y1, x2, y2)
        val classId: Int,          // Class ID
        val className: String,     // Class name
        val confidence: Float,     // Confidence score
        val mask: FloatArray? = null  // Optional segmentation mask
    ) {
        override fun toString(): String {
            return "Detection(class=$className, conf=${"%.2f".format(confidence)}, bbox=${bbox})"
        }
    }
    
    /**
     * Initialize ONNX Runtime and load model
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            Timber.w("YoloRunner already initialized")
            return true
        }
        
        return try {
            Timber.i("Initializing YOLOv8 runner: model=$modelPath")
            
            // Create ONNX Runtime environment
            ortEnv = OrtEnvironment.getEnvironment(
                OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING,
                "YoloRunner"
            )
            
            // Configure session options
            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

                // Threads
                setIntraOpNumThreads(config.numThreads)
                setInterOpNumThreads(1)

                // Memory
                setMemoryPatternOptimization(true)
                setCPUArenaAllocator(true)

                // NNAPI (optional)
                if (config.useGpu) {
                    try {
                        addNnapi()
                        Timber.i("NNAPI enabled")
                    } catch (e: Exception) {
                        Timber.w(e, "NNAPI not available, falling back to CPU")
                    }
                }

                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            }
            
            // Load model
            val modelFile = resolveModelPath()
            ortSession = ortEnv.createSession(modelFile, sessionOptions)
            
            // Log model info
            logModelInfo()
            
            isInitialized = true
            Timber.i("✓ YOLOv8 runner initialized")
            true
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize YOLOv8 runner")
            false
        }
    }
    
    /**
     * Run object detection on bitmap
     */
    suspend fun detect(bitmap: Bitmap): List<Detection> = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            throw IllegalStateException("YoloRunner not initialized")
        }
        
        val startTime = System.currentTimeMillis()
        
        // Preprocessing
        val preprocessed = prePost.preprocess(bitmap)
        val preprocessTime = System.currentTimeMillis() - startTime
        
        // Inference
        val inferenceStart = System.currentTimeMillis()
        val output = runInference(preprocessed.inputTensor)
        val inferenceTime = System.currentTimeMillis() - inferenceStart
        
        // Postprocessing
        val postprocessStart = System.currentTimeMillis()
        val detections = prePost.postprocess(
            output = output,
            originalWidth = bitmap.width,
            originalHeight = bitmap.height,
            preprocessInfo = preprocessed
        )
        val postprocessTime = System.currentTimeMillis() - postprocessStart
        
        val totalTime = System.currentTimeMillis() - startTime
        
        Timber.d(
            "Detection complete: preprocess=${preprocessTime}ms, " +
            "inference=${inferenceTime}ms, postprocess=${postprocessTime}ms, " +
            "total=${totalTime}ms, detections=${detections.size}"
        )
        
        detections
    }
    
    /**
     * Run inference on preprocessed input
     */
    private fun runInference(inputTensor: FloatBuffer): FloatArray {
        // Create input tensor
        val inputName = ortSession.inputNames.first()
        val inputShape = longArrayOf(1, 3, config.inputHeight.toLong(), config.inputWidth.toLong())
        
        val tensor = OnnxTensor.createTensor(ortEnv, inputTensor, inputShape)
        
        // Run inference
        val inputs = mapOf(inputName to tensor)
        val outputs = ortSession.run(inputs)
        
        // Extract output
        val outputTensor = outputs.first().value as OnnxTensor
        val outputBuffer = outputTensor.floatBuffer
        
        val outputArray = FloatArray(outputBuffer.remaining())
        outputBuffer.get(outputArray)
        
        // Cleanup
        tensor.close()
        outputs.forEach { it.value.close() }
        
        return outputArray
    }
    
    /**
     * Resolve model path (from assets or file system)
     */
    private fun resolveModelPath(): String {
        // Check if it's an absolute path
        val file = File(modelPath)
        if (file.exists()) {
            return file.absolutePath
        }
        
        // Try loading from assets
        val cacheFile = File(context.cacheDir, modelPath.substringAfterLast('/'))
        if (!cacheFile.exists()) {
            context.assets.open(modelPath).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        
        return cacheFile.absolutePath
    }
    
    /**
     * Log model information
     */
    private fun logModelInfo() {
        Timber.i("Model info:")
        Timber.i("  Inputs: ${ortSession.inputNames}")
        Timber.i("  Outputs: ${ortSession.outputNames}")
        
        ortSession.inputInfo.forEach { (name, info) ->
            val tensorInfo = info.info as TensorInfo
            Timber.i("  Input '$name': shape=${tensorInfo.shape.contentToString()}, type=${tensorInfo.type}")
        }
        
        ortSession.outputInfo.forEach { (name, info) ->
            val tensorInfo = info.info as TensorInfo
            Timber.i("  Output '$name': shape=${tensorInfo.shape.contentToString()}, type=${tensorInfo.type}")
        }
    }
    
    /**
     * Release resources
     */
    fun release() {
        if (isInitialized) {
            try {
                ortSession.close()
                ortEnv.close()
                isInitialized = false
                Timber.i("YOLOv8 runner released")
            } catch (e: Exception) {
                Timber.e(e, "Error releasing YOLOv8 runner")
            }
        }
    }
}

// Removed legacy/private execution provider helpers. Use public APIs (addNnapi) and session options instead.
