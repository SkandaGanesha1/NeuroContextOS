package com.cortexn.aura

import java.util.UUID

/**
 * Specification for an ML inference task to be scheduled by AURA
 * Declares model requirements, QoS constraints, and execution preferences
 */
data class TaskSpec(
    /** Unique identifier for this task */
    val id: String = UUID.randomUUID().toString(),
    
    /** Model identifier (path or registry key) */
    val modelId: String,
    
    /** Input tensor shapes (e.g., [1, 3, 224, 224] for image) */
    val inputShapes: List<IntArray>,
    
    /** Preferred precision (fp32, fp16, int8, int4) */
    val precision: Precision = Precision.FP16,
    
    /** Target backend (auto-selected if null) */
    val preferredBackend: BackendType? = null,
    
    /** Batch size for inference */
    val batchSize: Int = 1,
    
    /** QoS constraints */
    val qos: QoSConstraints = QoSConstraints(),
    
    /** Task priority (0=lowest, 10=highest) */
    val priority: Int = 5,
    
    /** Enable warm-up run before measurement */
    val enableWarmup: Boolean = true,
    
    /** Optional metadata for telemetry */
    val metadata: Map<String, Any> = emptyMap()
) {
    enum class Precision {
        FP32, FP16, INT8, INT4
    }
    
    enum class BackendType {
        ONNX_RUNTIME,
        EXECUTORCH,
        NNAPI,
        GPU_DELEGATE,
        XNNPACK
    }
    
    data class QoSConstraints(
        /** Maximum acceptable latency (ms) */
        val maxLatencyMs: Float? = null,
        
        /** Maximum energy budget per inference (joules) */
        val maxEnergyJoules: Float? = null,
        
        /** Minimum acceptable accuracy (0.0-1.0) */
        val minAccuracy: Float? = null,
        
        /** Allow throttling if device is thermal-constrained */
        val allowThrottling: Boolean = true,
        
        /** Deadline for task completion (timestamp) */
        val deadline: Long? = null
    )
    
    /**
     * Validates the task specification
     * @throws IllegalArgumentException if specification is invalid
     */
    fun validate() {
        require(modelId.isNotBlank()) { "Model ID cannot be blank" }
        require(inputShapes.isNotEmpty()) { "Input shapes must be specified" }
        require(batchSize > 0) { "Batch size must be positive" }
        require(priority in 0..10) { "Priority must be in range [0, 10]" }
        
        qos.maxLatencyMs?.let { require(it > 0) { "Max latency must be positive" } }
        qos.maxEnergyJoules?.let { require(it > 0) { "Max energy must be positive" } }
        qos.minAccuracy?.let { require(it in 0.0..1.0) { "Accuracy must be in [0.0, 1.0]" } }
    }
    
    /**
     * Creates a copy with modified QoS constraints
     */
    fun withQoS(block: QoSConstraints.() -> QoSConstraints): TaskSpec {
        return copy(qos = block(qos))
    }
    
    /**
     * Estimates memory footprint (rough approximation in MB)
     */
    fun estimateMemoryMB(): Float {
        val totalElements = inputShapes.fold(1) { acc, shape -> 
            acc * shape.fold(1) { s, dim -> s * dim }
        } * batchSize
        
        val bytesPerElement = when (precision) {
            Precision.FP32 -> 4
            Precision.FP16 -> 2
            Precision.INT8 -> 1
            Precision.INT4 -> 0.5f
        }
        
        return (totalElements * bytesPerElement) / (1024f * 1024f)
    }
    
    companion object {
        /**
         * Creates a TaskSpec for image classification
         */
        fun forImageClassification(
            modelId: String,
            imageSize: Int = 224,
            batchSize: Int = 1,
            precision: Precision = Precision.INT8
        ): TaskSpec {
            return TaskSpec(
                modelId = modelId,
                inputShapes = listOf(intArrayOf(batchSize, 3, imageSize, imageSize)),
                precision = precision,
                batchSize = batchSize,
                qos = QoSConstraints(maxLatencyMs = 50f)
            )
        }
        
        /**
         * Creates a TaskSpec for audio processing (e.g., Whisper)
         */
        fun forAudioProcessing(
            modelId: String,
            melBins: Int = 80,
            timeSteps: Int = 3000,
            precision: Precision = Precision.FP16
        ): TaskSpec {
            return TaskSpec(
                modelId = modelId,
                inputShapes = listOf(intArrayOf(1, melBins, timeSteps)),
                precision = precision,
                qos = QoSConstraints(maxLatencyMs = 100f)
            )
        }
        
        /**
         * Creates a TaskSpec for text generation (e.g., Llama)
         */
        fun forTextGeneration(
            modelId: String,
            seqLength: Int = 512,
            precision: Precision = Precision.INT4
        ): TaskSpec {
            return TaskSpec(
                modelId = modelId,
                inputShapes = listOf(intArrayOf(1, seqLength)),
                precision = precision,
                qos = QoSConstraints(
                    maxLatencyMs = 200f,
                    allowThrottling = true
                )
            )
        }
    }
}

/**
 * Result of a task execution
 */
data class TaskResult(
    val taskId: String,
    val outputs: List<FloatArray>,
    val metrics: ExecutionMetrics,
    val backend: TaskSpec.BackendType,
    val success: Boolean,
    val error: Throwable? = null
)

/**
 * Execution metrics for telemetry
 */
data class ExecutionMetrics(
    val latencyMs: Float,
    val energyJoules: Float,
    val powerWatts: Float,
    val cpuUtilization: Float,
    val gpuUtilization: Float?,
    val thermalState: ThermalState,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class ThermalState {
        NORMAL, LIGHT, MODERATE, SEVERE, CRITICAL
    }
    
    fun meetsConstraints(qos: TaskSpec.QoSConstraints): Boolean {
        qos.maxLatencyMs?.let { if (latencyMs > it) return false }
        qos.maxEnergyJoules?.let { if (energyJoules > it) return false }
        return true
    }
}
