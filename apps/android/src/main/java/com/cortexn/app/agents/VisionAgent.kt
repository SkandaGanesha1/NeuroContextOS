package com.cortexn.app.agents

import android.content.Context
import android.graphics.Bitmap
import com.cortexn.agents.vision.YoloRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Vision Agent - Object Detection
 * 
 * Wraps YoloRunner with ONNX Runtime + XNNPACK
 * Provides high-level interface for:
 * - Real-time object detection
 * - Scene understanding
 * - Visual navigation
 */
class VisionAgent(private val context: Context) {
    
    private lateinit var yoloRunner: YoloRunner
    private var isInitialized = false
    
    /**
     * Initialize YOLO model
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.i("Initializing Vision Agent (YOLOv8)...")
            
            yoloRunner = YoloRunner(
                context = context,
                modelPath = "models/yolov8n.onnx",
                config = YoloRunner.YoloConfig(
                    inputWidth = 640,
                    inputHeight = 640,
                    confidenceThreshold = 0.25f,
                    iouThreshold = 0.45f,
                    useGpu = false,
                    numThreads = 4
                )
            )
            
            isInitialized = yoloRunner.initialize()
            
            if (isInitialized) {
                Timber.i("✓ Vision Agent initialized")
            }
            
            isInitialized
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Vision Agent")
            false
        }
    }
    
    /**
     * Detect objects in image
     */
    suspend fun detect(bitmap: Bitmap): DetectionResult = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            throw IllegalStateException("Vision Agent not initialized")
        }
        
        val startTime = System.currentTimeMillis()
        
        val detections = yoloRunner.detect(bitmap)
        
        val latency = System.currentTimeMillis() - startTime
        
        DetectionResult(
            detections = detections.map { detection ->
                Detection(
                    className = detection.className,
                    confidence = detection.confidence,
                    boundingBox = BoundingBox(
                        x1 = detection.bbox.left,
                        y1 = detection.bbox.top,
                        x2 = detection.bbox.right,
                        y2 = detection.bbox.bottom
                    )
                )
            },
            latencyMs = latency,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height
        )
    }
    
    /**
     * Describe scene in natural language
     */
    fun describeScene(result: DetectionResult): String {
        if (result.detections.isEmpty()) {
            return "No objects detected in the scene."
        }
        
        val objectCounts = result.detections
            .groupBy { it.className }
            .mapValues { it.value.size }
        
        val description = objectCounts.entries.joinToString(", ") { (className, count) ->
            if (count == 1) className else "$count ${className}s"
        }
        
        return "I see: $description"
    }
    
    fun shutdown() {
        if (isInitialized) {
            yoloRunner.release()
            isInitialized = false
        }
    }
    
    data class DetectionResult(
        val detections: List<Detection>,
        val latencyMs: Long,
        val imageWidth: Int,
        val imageHeight: Int
    )
    
    data class Detection(
        val className: String,
        val confidence: Float,
        val boundingBox: BoundingBox
    )
    
    data class BoundingBox(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float
    ) {
        val centerX: Float get() = (x1 + x2) / 2
        val centerY: Float get() = (y1 + y2) / 2
        val width: Float get() = x2 - x1
        val height: Float get() = y2 - y1
    }
}
