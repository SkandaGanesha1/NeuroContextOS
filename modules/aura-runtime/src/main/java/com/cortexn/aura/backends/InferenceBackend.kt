package com.cortexn.aura.backends

import com.cortexn.aura.TaskSpec

/**
 * Common interface for all ML inference backends
 */
interface InferenceBackend {
    /**
     * Executes inference with the given task specification
     * @return List of output tensors as FloatArrays
     */
    suspend fun infer(spec: TaskSpec): List<FloatArray>
    
    /**
     * Runs warmup iterations to stabilize performance
     */
    suspend fun warmup(spec: TaskSpec)
    
    /**
     * Releases backend resources
     */
    fun release()
}
