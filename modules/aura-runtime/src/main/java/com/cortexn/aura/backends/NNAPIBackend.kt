package com.cortexn.aura.backends

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.cortexn.aura.TaskSpec
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.nnapi.NnApiDelegate
import timber.log.Timber
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * NNAPI backend for hardware acceleration
 * 
 * Utilizes Android Neural Networks API to leverage:
 * - DSP/NPU acceleration (Qualcomm Hexagon, Samsung NPU)
 * - GPU compute (via GPU delegate fallback)
 * - Quantized model execution
 */
@RequiresApi(Build.VERSION_CODES.P)
class NNAPIBackend(private val context: Context) : InferenceBackend {
    
    private val interpreters = mutableMapOf<String, Interpreter>()
    private val delegates = mutableListOf<Delegate>()
    
    init {
        Timber.i("✓ NNAPI backend initialized (Android ${Build.VERSION.SDK_INT})")
    }
    
    override suspend fun infer(spec: TaskSpec): List<FloatArray> {
        val interpreter = getOrCreateInterpreter(spec)
        
        // Allocate input/output buffers
        val inputs = allocateInputBuffers(spec, interpreter)
        val outputs = allocateOutputBuffers(interpreter)
        
        // Run inference
        val startTime = System.nanoTime()
        interpreter.runForMultipleInputsOutputs(inputs.toTypedArray(), outputs)
        val inferenceTime = (System.nanoTime() - startTime) / 1_000_000f
        
        Timber.d("NNAPI inference completed: ${inferenceTime}ms")
        
        // Extract results
        return outputs.map { buffer ->
            val array = FloatArray(buffer.remaining() / 4)
            buffer.asFloatBuffer().get(array)
            array
        }
    }
    
    override suspend fun warmup(spec: TaskSpec) {
        Timber.d("Warming up NNAPI backend for ${spec.modelId}")
        repeat(2) {
            infer(spec)
        }
    }
    
    private fun getOrCreateInterpreter(spec: TaskSpec): Interpreter {
        return interpreters.getOrPut(spec.modelId) {
            createInterpreter(spec)
        }
    }
    
    private fun createInterpreter(spec: TaskSpec): Interpreter {
        Timber.i("Creating NNAPI interpreter for ${spec.modelId}")
        
        val modelBuffer = loadModelFile(spec.modelId)
        
        val options = Interpreter.Options().apply {
            // Thread configuration
            setNumThreads(getOptimalThreadCount())
            
            // Try NNAPI first
            val nnapiDelegate = NnApiDelegate(
                NnApiDelegate.Options().apply {
                    setAllowFp16(spec.precision == TaskSpec.Precision.FP16)
                    setUseNnapiCpu(false) // Prefer accelerators
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                    }
                }
            )
            
            try {
                addDelegate(nnapiDelegate)
                delegates.add(nnapiDelegate)
                Timber.i("✓ NNAPI delegate added")
            } catch (e: Exception) {
                Timber.w(e, "Failed to add NNAPI delegate, trying GPU...")
                
                // Fallback to GPU if available
                if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                    val gpuDelegate = org.tensorflow.lite.gpu.GpuDelegate()
                    addDelegate(gpuDelegate)
                    delegates.add(gpuDelegate)
                    Timber.i("✓ GPU delegate added as fallback")
                }
            }
        }
        
        return Interpreter(modelBuffer, options)
    }
    
    private fun loadModelFile(modelId: String): MappedByteBuffer {
        val modelPath = resolveModelPath(modelId)
        
        FileInputStream(modelPath).use { inputStream ->
            val fileChannel = inputStream.channel
            return fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                0,
                fileChannel.size()
            )
        }
    }
    
    private fun allocateInputBuffers(
        spec: TaskSpec,
        interpreter: Interpreter
    ): List<ByteBuffer> {
        return spec.inputShapes.mapIndexed { index, shape ->
            val inputSize = shape.fold(1) { acc, dim -> acc * dim }
            val bytesPerElement = when (spec.precision) {
                TaskSpec.Precision.FP32 -> 4
                TaskSpec.Precision.FP16 -> 2
                TaskSpec.Precision.INT8 -> 1
                TaskSpec.Precision.INT4 -> 1 // Packed
            }
            
            ByteBuffer.allocateDirect(inputSize * bytesPerElement).apply {
                order(ByteOrder.nativeOrder())
                
                // Fill with dummy data
                when (spec.precision) {
                    TaskSpec.Precision.FP32, TaskSpec.Precision.FP16 -> {
                        val floatBuffer = asFloatBuffer()
                        repeat(inputSize) {
                            floatBuffer.put((Math.random() * 2 - 1).toFloat())
                        }
                    }
                    TaskSpec.Precision.INT8, TaskSpec.Precision.INT4 -> {
                        repeat(inputSize * bytesPerElement) {
                            put((Math.random() * 255).toByte())
                        }
                    }
                }
                
                rewind()
            }
        }
    }
    
    private fun allocateOutputBuffers(interpreter: Interpreter): List<ByteBuffer> {
        return (0 until interpreter.outputTensorCount).map { index ->
            val tensor = interpreter.getOutputTensor(index)
            val size = tensor.shape().fold(1) { acc, dim -> acc * dim }
            
            ByteBuffer.allocateDirect(size * 4).apply {
                order(ByteOrder.nativeOrder())
            }
        }
    }
    
    private fun resolveModelPath(modelId: String): String {
        // Check cache
        val cacheFile = java.io.File(context.cacheDir, modelId)
        if (cacheFile.exists()) {
            return cacheFile.absolutePath
        }
        
        // Check assets
        try {
            context.assets.open("models/$modelId").use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return cacheFile.absolutePath
        } catch (e: Exception) {
            // Check file system
            val file = java.io.File(modelId)
            if (file.exists()) {
                return file.absolutePath
            }
            throw IllegalArgumentException("Model not found: $modelId", e)
        }
    }
    
    private fun getOptimalThreadCount(): Int {
        return maxOf(1, (Runtime.getRuntime().availableProcessors() * 0.75).toInt())
    }
    
    override fun release() {
        Timber.i("Releasing NNAPI backend resources")
        interpreters.values.forEach { it.close() }
        interpreters.clear()
        delegates.forEach { it.close() }
        delegates.clear()
    }
}
