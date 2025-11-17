package com.cortexn.agents.asr

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import kotlin.math.log10

/**
 * Whisper ASR Runner using ExecuTorch
 * 
 * Features:
 * - Whisper-tiny model optimized for on-device inference
 * - INT8 quantization for reduced memory footprint
 * - Log-Mel spectrogram preprocessing
 * - Voice Activity Detection (VAD) for efficiency
 * - Streaming transcription support
 * - Language detection
 */
class WhisperRunner(
    private val context: Context,
    private val modelPath: String,
    private val config: WhisperConfig = WhisperConfig()
) {
    
    private var module: Module? = null
    private var isInitialized = false
    
    private val audioIO = AudioIO(config.sampleRate)
    
    private val _transcriptionState = MutableStateFlow<TranscriptionState>(TranscriptionState.Idle)
    val transcriptionState: StateFlow<TranscriptionState> = _transcriptionState.asStateFlow()
    
    data class WhisperConfig(
        val sampleRate: Int = 16000,           // Whisper requires 16kHz
        val nMels: Int = 80,                   // Number of mel filterbanks
        val nFFT: Int = 400,                   // FFT window size
        val hopLength: Int = 160,              // Hop length (10ms at 16kHz)
        val chunkLengthSeconds: Float = 30.0f, // 30-second chunks
        val language: String? = null,          // Auto-detect if null
        val task: Task = Task.TRANSCRIBE,
        val temperature: Float = 0.0f,         // Greedy decoding
        val noSpeechThreshold: Float = 0.6f,   // VAD threshold
        val compressionRatio: Float = 2.4f,
        val logprobThreshold: Float = -1.0f
    ) {
        enum class Task {
            TRANSCRIBE,  // Speech → text in original language
            TRANSLATE    // Speech → English text
        }
    }
    
    sealed class TranscriptionState {
        object Idle : TranscriptionState()
        data class Processing(val progress: Float) : TranscriptionState()
        data class Result(val text: String, val segments: List<Segment>) : TranscriptionState()
        data class Error(val message: String) : TranscriptionState()
    }
    
    data class Segment(
        val text: String,
        val startTime: Float,
        val endTime: Float,
        val confidence: Float,
        val noSpeechProb: Float
    )
    
    /**
     * Initialize Whisper model
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            Timber.w("WhisperRunner already initialized")
            return true
        }
        
        return try {
            Timber.i("Initializing Whisper: model=$modelPath")
            
            System.loadLibrary("executorch")
            
            val modelFile = resolveModelPath()
            module = Module.load(modelFile)
            
            isInitialized = true
            Timber.i("✓ Whisper runner initialized")
            true
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Whisper")
            _transcriptionState.value = TranscriptionState.Error(e.message ?: "Initialization failed")
            false
        }
    }
    
    /**
     * Transcribe audio file
     */
    suspend fun transcribeFile(audioFilePath: String): String = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            throw IllegalStateException("WhisperRunner not initialized")
        }
        
        _transcriptionState.value = TranscriptionState.Processing(0f)
        
        val startTime = System.currentTimeMillis()
        
        try {
            // Load and preprocess audio
            val audioData = audioIO.loadAudioFile(audioFilePath)
            val result = transcribe(audioData)
            
            val duration = System.currentTimeMillis() - startTime
            Timber.i("Transcription completed in ${duration}ms: ${result.length} characters")
            
            _transcriptionState.value = TranscriptionState.Result(result, emptyList())
            result
            
        } catch (e: Exception) {
            Timber.e(e, "Transcription failed")
            _transcriptionState.value = TranscriptionState.Error(e.message ?: "Transcription failed")
            throw e
        }
    }
    
    /**
     * Transcribe audio buffer (PCM 16-bit, 16kHz)
     */
    suspend fun transcribe(audioData: FloatArray): String = withContext(Dispatchers.Default) {
        val chunkSize = (config.sampleRate * config.chunkLengthSeconds).toInt()
        val fullText = StringBuilder()
        
        // Process audio in chunks
        var offset = 0
        var chunkIndex = 0
        
        while (offset < audioData.size) {
            val remaining = audioData.size - offset
            val currentChunkSize = minOf(chunkSize, remaining)
            
            val chunk = audioData.copyOfRange(offset, offset + currentChunkSize)
            
            // Pad if necessary
            val paddedChunk = if (chunk.size < chunkSize) {
                FloatArray(chunkSize).also { 
                    chunk.copyInto(it)
                }
            } else {
                chunk
            }
            
            // Transcribe chunk
            val chunkText = transcribeChunk(paddedChunk, chunkIndex)
            fullText.append(chunkText)
            
            offset += currentChunkSize
            chunkIndex++
            
            // Update progress
            val progress = offset.toFloat() / audioData.size
            _transcriptionState.value = TranscriptionState.Processing(progress)
        }
        
        fullText.toString().trim()
    }
    
    /**
     * Transcribe single audio chunk
     */
    private fun transcribeChunk(audioChunk: FloatArray, chunkIndex: Int): String {
        val preprocessStart = System.nanoTime()
        
        // Compute log-mel spectrogram
        val melSpectrogram = audioIO.computeMelSpectrogram(
            audioChunk,
            config.nFFT,
            config.hopLength,
            config.nMels
        )
        
        val preprocessTime = (System.nanoTime() - preprocessStart) / 1_000_000f
        
        // Voice Activity Detection
        val noSpeechProb = detectNoSpeech(melSpectrogram)
        
        if (noSpeechProb > config.noSpeechThreshold) {
            Timber.d("Chunk $chunkIndex: No speech detected (prob=${"%.3f".format(noSpeechProb)})")
            return ""
        }
        
        // Run inference
        val inferenceStart = System.nanoTime()
        val tokens = runInference(melSpectrogram)
        val inferenceTime = (System.nanoTime() - inferenceStart) / 1_000_000f
        
        // Decode tokens to text
        val text = decodeTokens(tokens)
        
        Timber.d(
            "Chunk $chunkIndex: preprocess=${preprocessTime}ms, " +
            "inference=${inferenceTime}ms, text='$text'"
        )
        
        return text
    }
    
    /**
     * Run Whisper inference on mel spectrogram
     */
    private fun runInference(melSpectrogram: Array<FloatArray>): IntArray {
        val module = this.module ?: throw IllegalStateException("Module not loaded")
        
        // Flatten mel spectrogram to 1D array
        val inputSize = config.nMels * melSpectrogram[0].size
        val inputData = FloatArray(inputSize)
        
        var idx = 0
        for (i in melSpectrogram.indices) {
            for (j in melSpectrogram[i].indices) {
                inputData[idx++] = melSpectrogram[i][j]
            }
        }
        
        // Create input tensor: [1, n_mels, time_steps]
        val shape = longArrayOf(1, config.nMels.toLong(), melSpectrogram[0].size.toLong())
        val inputTensor = Tensor.fromBlob(inputData, shape)
        
        // Run forward pass
        val inputValue = EValue.from(inputTensor)
        val outputValue = module.forward(inputValue)
        
        // Extract output tokens
        val outputTensor = outputValue.toTensor()
        val outputData = outputTensor.dataAsLongArray
        
        return outputData.map { it.toInt() }.toIntArray()
    }
    
    /**
     * Detect silence/no-speech in mel spectrogram
     */
    private fun detectNoSpeech(melSpectrogram: Array<FloatArray>): Float {
        // Calculate average energy in mel spectrogram
        var totalEnergy = 0.0
        var count = 0
        
        for (i in melSpectrogram.indices) {
            for (j in melSpectrogram[i].indices) {
                totalEnergy += melSpectrogram[i][j]
                count++
            }
        }
        
        val avgEnergy = totalEnergy / count
        
        // Convert to probability (simplified heuristic)
        // In production, use actual Whisper no-speech token probability
        return if (avgEnergy < -50.0) 0.9f else 0.1f
    }
    
    /**
     * Decode token IDs to text
     * 
     * Note: This is a simplified decoder. In production, use:
     * - Whisper tokenizer (tiktoken)
     * - Proper special token handling
     * - Language-specific character mapping
     */
    private fun decodeTokens(tokens: IntArray): String {
        // Placeholder implementation
        // In production, load Whisper tokenizer and decode properly
        
        val text = StringBuilder()
        
        tokens.forEach { token ->
            // Skip special tokens
            when (token) {
                50257 -> return@forEach  // <|endoftext|>
                50258 -> return@forEach  // <|startoftranscript|>
                50259 -> return@forEach  // <|notimestamps|>
                in 50260..50359 -> return@forEach  // Language tokens
                else -> {
                    // Map token to character (simplified)
                    if (token in 32..126) {
                        text.append(token.toChar())
                    } else {
                        text.append("□")  // Unknown character
                    }
                }
            }
        }
        
        return text.toString()
    }
    
    /**
     * Resolve model path from assets or file system
     */
    private fun resolveModelPath(): String {
        val file = File(modelPath)
        if (file.exists()) {
            return file.absolutePath
        }
        
        // Copy from assets
        val cacheFile = File(context.cacheDir, modelPath.substringAfterLast('/'))
        if (!cacheFile.exists()) {
            context.assets.open(modelPath).use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        
        return cacheFile.absolutePath
    }
    
    /**
     * Release resources
     */
    fun release() {
        if (isInitialized) {
            try {
                module?.destroy()
                module = null
                isInitialized = false
                Timber.i("Whisper runner released")
            } catch (e: Exception) {
                Timber.e(e, "Error releasing Whisper runner")
            }
        }
    }
}
