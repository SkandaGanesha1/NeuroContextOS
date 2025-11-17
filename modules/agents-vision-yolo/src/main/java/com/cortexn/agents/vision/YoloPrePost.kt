package com.cortexn.agents.vision

import android.graphics.Bitmap
import android.graphics.RectF
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 Preprocessing and Postprocessing
 * 
 * Implements:
 * - Letterbox resizing with aspect ratio preservation
 * - RGB normalization (0-255 → 0-1)
 * - CHW format conversion (NCHW for ONNX)
 * - NMS (Non-Maximum Suppression) with IoU filtering
 * - Coordinate transformation back to original image space
 */
class YoloPrePost(private val config: YoloRunner.YoloConfig) {
    
    /**
     * Preprocessing result containing input tensor and metadata
     */
    data class PreprocessResult(
        val inputTensor: FloatBuffer,
        val scale: Float,           // Scale factor applied
        val padLeft: Int,           // Padding on left
        val padTop: Int,            // Padding on top
        val inputWidth: Int,        // Final input width
        val inputHeight: Int        // Final input height
    )
    
    /**
     * Preprocess bitmap for YOLO inference
     * 
     * Steps:
     * 1. Letterbox resize (maintain aspect ratio)
     * 2. Convert to RGB float array
     * 3. Normalize to [0, 1]
     * 4. Convert to CHW format (channels-first)
     */
    fun preprocess(bitmap: Bitmap): PreprocessResult {
        val startTime = System.nanoTime()
        
        // Calculate letterbox parameters
        val (scaledBitmap, scale, padLeft, padTop) = letterboxResize(
            bitmap,
            config.inputWidth,
            config.inputHeight
        )
        
        // Allocate buffer for CHW format: [1, 3, H, W]
        val inputSize = 3 * config.inputHeight * config.inputWidth
        val buffer = ByteBuffer.allocateDirect(inputSize * 4).order(ByteOrder.nativeOrder())
        val floatBuffer = buffer.asFloatBuffer()
        
        // Convert to float array and normalize
        val pixels = IntArray(scaledBitmap.width * scaledBitmap.height)
        scaledBitmap.getPixels(
            pixels,
            0,
            scaledBitmap.width,
            0,
            0,
            scaledBitmap.width,
            scaledBitmap.height
        )
        
        // Convert RGB to CHW format with normalization
        // ONNX expects NCHW format: [batch, channels, height, width]
        val width = scaledBitmap.width
        val height = scaledBitmap.height
        
        for (c in 0 until 3) {  // Channels (R, G, B)
            for (h in 0 until height) {
                for (w in 0 until width) {
                    val pixel = pixels[h * width + w]
                    
                    val value = when (c) {
                        0 -> ((pixel shr 16) and 0xFF) / 255.0f  // R
                        1 -> ((pixel shr 8) and 0xFF) / 255.0f   // G
                        else -> (pixel and 0xFF) / 255.0f        // B
                    }
                    
                    floatBuffer.put(value)
                }
            }
        }
        
        floatBuffer.rewind()
        
        val preprocessTime = (System.nanoTime() - startTime) / 1_000_000f
        Timber.d("Preprocessing completed in ${preprocessTime}ms")
        
        return PreprocessResult(
            inputTensor = floatBuffer,
            scale = scale,
            padLeft = padLeft,
            padTop = padTop,
            inputWidth = config.inputWidth,
            inputHeight = config.inputHeight
        )
    }
    
    /**
     * Letterbox resize to maintain aspect ratio
     * 
     * @return Tuple of (resized bitmap, scale, pad_left, pad_top)
     */
    private fun letterboxResize(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Tuple4<Bitmap, Float, Int, Int> {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        
        // Calculate scale to fit within target size
        val scale = min(
            targetWidth.toFloat() / originalWidth,
            targetHeight.toFloat() / originalHeight
        )
        
        val scaledWidth = (originalWidth * scale).toInt()
        val scaledHeight = (originalHeight * scale).toInt()
        
        // Calculate padding to center the image
        val padLeft = (targetWidth - scaledWidth) / 2
        val padTop = (targetHeight - scaledHeight) / 2
        
        // Create scaled bitmap
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        
        // Create padded bitmap with gray background
        val paddedBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(paddedBitmap)
        
        // Fill with gray (114, 114, 114)
        canvas.drawColor(android.graphics.Color.rgb(114, 114, 114))
        
        // Draw scaled image
        canvas.drawBitmap(scaledBitmap, padLeft.toFloat(), padTop.toFloat(), null)
        
        return Tuple4(paddedBitmap, scale, padLeft, padTop)
    }
    
    /**
     * Postprocess YOLO output
     * 
     * YOLOv8 output format: [1, 84, 8400]
     * - 84 = 4 bbox coords + 80 class scores
     * - 8400 = number of predictions (80x80 + 40x40 + 20x20 grids)
     */
    fun postprocess(
        output: FloatArray,
        originalWidth: Int,
        originalHeight: Int,
        preprocessInfo: PreprocessResult
    ): List<YoloRunner.Detection> {
        val startTime = System.nanoTime()
        
        // Parse output: [1, 84, 8400] → [8400, 84]
        val numPredictions = 8400
        val numElements = 4 + config.numClasses  // 4 bbox + 80 classes
        
        val predictions = mutableListOf<RawPrediction>()
        
        for (i in 0 until numPredictions) {
            // Extract bbox coordinates (center_x, center_y, width, height)
            val cx = output[i]
            val cy = output[numPredictions + i]
            val w = output[2 * numPredictions + i]
            val h = output[3 * numPredictions + i]
            
            // Find max class score and class ID
            var maxScore = 0.0f
            var maxClassId = -1
            
            for (c in 0 until config.numClasses) {
                val score = output[(4 + c) * numPredictions + i]
                if (score > maxScore) {
                    maxScore = score
                    maxClassId = c
                }
            }
            
            // Filter by confidence threshold
            if (maxScore >= config.confidenceThreshold) {
                // Convert to (x1, y1, x2, y2) format
                val x1 = cx - w / 2
                val y1 = cy - h / 2
                val x2 = cx + w / 2
                val y2 = cy + h / 2
                
                predictions.add(
                    RawPrediction(
                        x1 = x1,
                        y1 = y1,
                        x2 = x2,
                        y2 = y2,
                        confidence = maxScore,
                        classId = maxClassId
                    )
                )
            }
        }
        
        Timber.d("Filtered ${predictions.size} predictions above threshold")
        
        // Apply NMS (Non-Maximum Suppression)
        val nmsResults = applyNMS(predictions)
        
        // Transform coordinates back to original image space
        val detections = nmsResults.map { pred ->
            transformCoordinates(pred, preprocessInfo, originalWidth, originalHeight)
        }
        
        val postprocessTime = (System.nanoTime() - startTime) / 1_000_000f
        Timber.d("Postprocessing completed in ${postprocessTime}ms, ${detections.size} final detections")
        
        return detections.take(config.maxDetections)
    }
    
    /**
     * Apply Non-Maximum Suppression (NMS)
     */
    private fun applyNMS(predictions: List<RawPrediction>): List<RawPrediction> {
        if (predictions.isEmpty()) return emptyList()
        
        // Sort by confidence (descending)
        val sorted = predictions.sortedByDescending { it.confidence }
        
        val selected = mutableListOf<RawPrediction>()
        val suppressed = BooleanArray(sorted.size)
        
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            
            val current = sorted[i]
            selected.add(current)
            
            // Suppress overlapping boxes
            for (j in (i + 1) until sorted.size) {
                if (suppressed[j]) continue
                
                val other = sorted[j]
                
                // Only compare boxes of the same class
                if (current.classId == other.classId) {
                    val iou = calculateIoU(current, other)
                    if (iou > config.iouThreshold) {
                        suppressed[j] = true
                    }
                }
            }
        }
        
        return selected
    }
    
    /**
     * Calculate Intersection over Union (IoU)
     */
    private fun calculateIoU(box1: RawPrediction, box2: RawPrediction): Float {
        val x1 = max(box1.x1, box2.x1)
        val y1 = max(box1.y1, box2.y1)
        val x2 = min(box1.x2, box2.x2)
        val y2 = min(box1.y2, box2.y2)
        
        val intersection = max(0f, x2 - x1) * max(0f, y2 - y1)
        
        val area1 = (box1.x2 - box1.x1) * (box1.y2 - box1.y1)
        val area2 = (box2.x2 - box2.x1) * (box2.y2 - box2.y1)
        
        val union = area1 + area2 - intersection
        
        return if (union > 0) intersection / union else 0f
    }
    
    /**
     * Transform coordinates from model space to original image space
     */
    private fun transformCoordinates(
        pred: RawPrediction,
        preprocessInfo: PreprocessResult,
        originalWidth: Int,
        originalHeight: Int
    ): YoloRunner.Detection {
        // Remove padding
        val x1 = (pred.x1 - preprocessInfo.padLeft) / preprocessInfo.scale
        val y1 = (pred.y1 - preprocessInfo.padTop) / preprocessInfo.scale
        val x2 = (pred.x2 - preprocessInfo.padLeft) / preprocessInfo.scale
        val y2 = (pred.y2 - preprocessInfo.padTop) / preprocessInfo.scale
        
        // Clamp to image bounds
        val bbox = RectF(
            max(0f, x1),
            max(0f, y1),
            min(originalWidth.toFloat(), x2),
            min(originalHeight.toFloat(), y2)
        )
        
        val className = if (pred.classId < config.labels.size) {
            config.labels[pred.classId]
        } else {
            "class_${pred.classId}"
        }
        
        return YoloRunner.Detection(
            bbox = bbox,
            classId = pred.classId,
            className = className,
            confidence = pred.confidence
        )
    }
    
    /**
     * Raw prediction before NMS
     */
    private data class RawPrediction(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val confidence: Float,
        val classId: Int
    )
    
    /**
     * Helper data class for 4-tuple
     */
    private data class Tuple4<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}
