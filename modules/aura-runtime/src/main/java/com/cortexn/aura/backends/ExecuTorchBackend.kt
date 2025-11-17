package com.cortexn.aura.backends

import android.content.Context
import com.cortexn.aura.TaskSpec
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * ExecuTorch backend for on-device LLM and audio models
 * 
 * Optimizations:
 * - Quantized models (INT4/INT8) via AI Edge Torch
 * - KV-cache for autoregressive generation
 * - Custom kernels for ARM NEON/I8MM
 * - Memory-mapped weights for reduced load time
 */
class ExecuTorchBackend(private val context: Context) : InferenceBackend {
    
    private val modules = mutableMapOf<String, Module>()
    
    init {
        System.loadLibrary("executorch")
        Timber.i("✓ ExecuTorch native library loaded")
    }
    
    override suspend fun infer(spec: TaskSpec): List<FloatArray> {
        val module = getOrCreateModule(spec)
        
        // Prepare input tensors
        val inputs = prepareInputTensors(spec)
        
        // Execute inference
        val startTime = System.nanoTime()
        val outputs = module.forward(*inputs)
        val inferenceTime = (System.nanoTime() - startTime) / 1_000_000f
        
        Timber.d("ExecuTorch inference completed: ${inferenceTime}ms")
        
        // Extract output values
        return extractOutputs(outputs)
    }
    
    override suspend fun warmup(spec: TaskSpec) {
        Timber.d("Warming up ExecuTorch backend for ${spec.modelId}")
        repeat(2) {
            infer(spec)
        }
    }
    
    private fun getOrCreateModule(spec: TaskSpec): Module {
        return modules.getOrPut(spec.modelId) {
            loadModule(spec.modelId)
        }
    }
    
    private fun loadModule(modelId: String): Module {
        Timber.i("Loading ExecuTorch module: $modelId")
        
        val modelPath = resolveModelPath(modelId)
        
        return Module.load(modelPath).also {
            Timber.i("✓ Module loaded: $modelId")
        }
    }
    
    private fun prepareInputTensors(spec: TaskSpec): Array<EValue> {
        return spec.inputShapes.map { shape ->
            when (spec.precision) {
                TaskSpec.Precision.FP32, TaskSpec.Precision.FP16 -> {
                    val data = FloatArray(shape.fold(1) { acc, dim -> acc * dim }) {
                        (Math.random() * 2 - 1).toFloat()
                    }
                    EValue.from(Tensor.fromBlob(data, shape.map { it.toLong() }.toLongArray()))
                }
                TaskSpec.Precision.INT8 -> {
                    val data = ByteArray(shape.fold(1) { acc, dim -> acc * dim }) {
                        (Math.random() * 255).toByte()
                    }
                    EValue.from(Tensor.fromBlob(data, shape.map { it.toLong() }.toLongArray()))
                }
                TaskSpec.Precision.INT4 -> {
                    // INT4 packed format (2 int4 values per byte)
                    val size = (shape.fold(1) { acc, dim -> acc * dim } + 1) / 2
                    val data = ByteArray(size) { (Math.random() * 255).toByte() }
                    EValue.from(Tensor.fromBlob(data, shape.map { it.toLong() }.toLongArray()))
                }
            }
        }.toTypedArray()
    }
    
    private fun extractOutputs(output: EValue): List<FloatArray> {
        return when {
            output.isTensor -> {
                val tensor = output.toTensor()
                listOf(tensor.dataAsFloatArray)
            }
            output.isTuple -> {
                output.toTuple().map { elem ->
                    if (elem.isTensor) {
                        elem.toTensor().dataAsFloatArray
                    } else {
                        floatArrayOf()
                    }
                }
            }
            output.isList -> {
                output.toList().map { elem ->
                    if (elem.isTensor) {
                        elem.toTensor().dataAsFloatArray
                    } else {
                        floatArrayOf()
                    }
                }
            }
            else -> emptyList()
        }
    }
    
    private fun resolveModelPath(modelId: String): String {
        // Check assets
        val assetsPath = "models/$modelId"
        val cacheFile = File(context.cacheDir, modelId)
        
        if (!cacheFile.exists()) {
            try {
                context.assets.open(assetsPath).use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Timber.d("Copied model to cache: ${cacheFile.absolutePath}")
            } catch (e: Exception) {
                // Try file system
                val file = File(modelId)
                if (file.exists()) {
                    return file.absolutePath
                }
                throw IllegalArgumentException("Model not found: $modelId", e)
            }
        }
        
        return cacheFile.absolutePath
    }
    
    override fun release() {
        Timber.i("Releasing ExecuTorch backend resources")
        modules.values.forEach { module ->
            try {
                module.destroy()
            } catch (e: Exception) {
                Timber.w(e, "Failed to destroy module")
            }
        }
        modules.clear()
    }
}
