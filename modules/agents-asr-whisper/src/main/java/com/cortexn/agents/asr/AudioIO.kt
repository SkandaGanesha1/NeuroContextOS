package com.cortexn.agents.asr

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jtransforms.fft.FloatFFT_1D
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Audio I/O utilities for Whisper ASR
 * 
 * Features:
 * - Real-time audio recording
 * - WAV file loading with resampling
 * - Log-Mel spectrogram computation
 * - VAD (Voice Activity Detection)
 * - Audio normalization and preprocessing
 */
class AudioIO(private val sampleRate: Int = 16000) {
    
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val recordingChannel = Channel<FloatArray>(Channel.UNLIMITED)
    
    companion object {
        private const val RECORDING_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val RECORDING_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 2
        
        // Mel filterbank parameters
        private const val MEL_MIN_HZ = 0f
        private const val MEL_MAX_HZ = 8000f
    }
    
    /**
     * Load audio file (WAV format)
     * Automatically resamples to target sample rate if needed
     */
    fun loadAudioFile(filePath: String): FloatArray {
        Timber.i("Loading audio file: $filePath")
        
        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("Audio file not found: $filePath")
        }
        
        return FileInputStream(file).use { input ->
            // Read WAV header (simplified - assumes standard WAV format)
            val header = ByteArray(44)
            input.read(header)
            
            val wavHeader = parseWavHeader(header)
            Timber.d("WAV: ${wavHeader.sampleRate}Hz, ${wavHeader.channels}ch, ${wavHeader.bitsPerSample}bit")
            
            // Read audio data
            val dataSize = file.length() - 44
            val audioBytes = ByteArray(dataSize.toInt())
            input.read(audioBytes)
            
            // Convert to float array
            val audioData = convertBytesToFloat(audioBytes, wavHeader.bitsPerSample)
            
            // Resample if needed
            val resampled = if (wavHeader.sampleRate != sampleRate) {
                Timber.i("Resampling from ${wavHeader.sampleRate}Hz to ${sampleRate}Hz")
                resampleAudio(audioData, wavHeader.sampleRate, sampleRate)
            } else {
                audioData
            }
            
            // Convert to mono if stereo
            val mono = if (wavHeader.channels == 2) {
                convertStereoToMono(resampled)
            } else {
                resampled
            }
            
            // Normalize to [-1, 1]
            normalizeAudio(mono)
        }
    }
    
    /**
     * Start real-time audio recording
     */
    fun startRecording(onAudioChunk: (FloatArray) -> Unit) {
        if (isRecording) {
            Timber.w("Recording already in progress")
            return
        }
        
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            RECORDING_CHANNEL,
            RECORDING_ENCODING
        )
        
        val bufferSize = minBufferSize * BUFFER_SIZE_MULTIPLIER
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            RECORDING_CHANNEL,
            RECORDING_ENCODING,
            bufferSize
        )
        
        audioRecord?.startRecording()
        isRecording = true
        
        Timber.i("Audio recording started: ${sampleRate}Hz, buffer=$bufferSize")
        
        // Start recording thread
        GlobalScope.launch(Dispatchers.IO) {
            val buffer = ShortArray(bufferSize / 2)
            
            while (isRecording) {
                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                
                if (readSize > 0) {
                    // Convert to float
                    val floatBuffer = FloatArray(readSize)
                    for (i in 0 until readSize) {
                        floatBuffer[i] = buffer[i] / 32768.0f
                    }
                    
                    onAudioChunk(floatBuffer)
                }
            }
        }
    }
    
    /**
     * Stop audio recording
     */
    fun stopRecording() {
        if (!isRecording) return
        
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        Timber.i("Audio recording stopped")
    }
    
    /**
     * Compute log-Mel spectrogram
     * 
     * Steps:
     * 1. Apply Hann window
     * 2. Compute STFT (Short-Time Fourier Transform)
     * 3. Apply Mel filterbank
     * 4. Convert to log scale
     */
    fun computeMelSpectrogram(
        audioData: FloatArray,
        nFFT: Int,
        hopLength: Int,
        nMels: Int
    ): Array<FloatArray> {
        val numFrames = (audioData.size - nFFT) / hopLength + 1
        
        // Initialize output
        val melSpectrogram = Array(nMels) { FloatArray(numFrames) }
        
        // Create Hann window
        val window = createHannWindow(nFFT)
        
        // Create Mel filterbank
        val melFilterbank = createMelFilterbank(nFFT, sampleRate, nMels)
        
        // Create FFT instance
        val fft = FloatFFT_1D(nFFT.toLong())
        
        // Process each frame
        for (frameIdx in 0 until numFrames) {
            val offset = frameIdx * hopLength
            
            // Extract frame and apply window
            val frame = FloatArray(nFFT * 2) // Complex numbers (real, imag)
            for (i in 0 until nFFT) {
                if (offset + i < audioData.size) {
                    frame[i * 2] = audioData[offset + i] * window[i]
                }
            }
            
            // Compute FFT
            fft.complexForward(frame)
            
            // Compute magnitude spectrum
            val magnitudes = FloatArray(nFFT / 2 + 1)
            for (i in magnitudes.indices) {
                val real = frame[i * 2]
                val imag = frame[i * 2 + 1]
                magnitudes[i] = sqrt(real * real + imag * imag)
            }
            
            // Apply Mel filterbank
            for (melIdx in 0 until nMels) {
                var melValue = 0.0f
                
                for (freqIdx in magnitudes.indices) {
                    melValue += magnitudes[freqIdx] * melFilterbank[melIdx][freqIdx]
                }
                
                // Convert to log scale (add epsilon to avoid log(0))
                melSpectrogram[melIdx][frameIdx] = log10(maxOf(melValue, 1e-10f)) * 20f
            }
        }
        
        return melSpectrogram
    }
    
    /**
     * Create Hann window function
     */
    private fun createHannWindow(size: Int): FloatArray {
        return FloatArray(size) { i ->
            0.5f * (1f - cos(2f * PI.toFloat() * i / (size - 1)))
        }
    }
    
    /**
     * Create Mel filterbank
     * 
     * Converts linear frequency bins to Mel scale
     */
    private fun createMelFilterbank(
        nFFT: Int,
        sampleRate: Int,
        nMels: Int
    ): Array<FloatArray> {
        val numFreqBins = nFFT / 2 + 1
        val filterbank = Array(nMels) { FloatArray(numFreqBins) }
        
        // Convert Hz to Mel scale
        val melMin = hzToMel(MEL_MIN_HZ)
        val melMax = hzToMel(MEL_MAX_HZ)
        
        // Create Mel-spaced frequency points
        val melPoints = FloatArray(nMels + 2)
        for (i in melPoints.indices) {
            val mel = melMin + (melMax - melMin) * i / (nMels + 1)
            melPoints[i] = melToHz(mel)
        }
        
        // Convert to FFT bin indices
        val fftBins = melPoints.map { freq ->
            ((nFFT + 1) * freq / sampleRate).toInt()
        }
        
        // Create triangular filters
        for (melIdx in 0 until nMels) {
            val leftBin = fftBins[melIdx]
            val centerBin = fftBins[melIdx + 1]
            val rightBin = fftBins[melIdx + 2]
            
            // Rising edge
            for (bin in leftBin until centerBin) {
                if (bin < numFreqBins) {
                    filterbank[melIdx][bin] = (bin - leftBin).toFloat() / (centerBin - leftBin)
                }
            }
            
            // Falling edge
            for (bin in centerBin until rightBin) {
                if (bin < numFreqBins) {
                    filterbank[melIdx][bin] = (rightBin - bin).toFloat() / (rightBin - centerBin)
                }
            }
        }
        
        return filterbank
    }
    
    /**
     * Convert Hz to Mel scale
     */
    private fun hzToMel(hz: Float): Float {
        return 2595f * log10(1f + hz / 700f)
    }
    
    /**
     * Convert Mel to Hz scale
     */
    private fun melToHz(mel: Float): Float {
        return 700f * (10f.pow(mel / 2595f) - 1f)
    }
    
    /**
     * Parse WAV file header
     */
    private fun parseWavHeader(header: ByteArray): WavHeader {
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        
        // Skip RIFF header
        buffer.position(22)
        val channels = buffer.short.toInt()
        
        buffer.position(24)
        val sampleRate = buffer.int
        
        buffer.position(34)
        val bitsPerSample = buffer.short.toInt()
        
        return WavHeader(sampleRate, channels, bitsPerSample)
    }
    
    /**
     * Convert byte array to float array
     */
    private fun convertBytesToFloat(bytes: ByteArray, bitsPerSample: Int): FloatArray {
        return when (bitsPerSample) {
            16 -> {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(bytes.size / 2) { i ->
                    buffer.getShort(i * 2) / 32768.0f
                }
            }
            else -> throw UnsupportedOperationException("Unsupported bits per sample: $bitsPerSample")
        }
    }
    
    /**
     * Resample audio data
     * Uses linear interpolation (simple but effective for speech)
     */
    private fun resampleAudio(
        audioData: FloatArray,
        originalSampleRate: Int,
        targetSampleRate: Int
    ): FloatArray {
        val ratio = originalSampleRate.toFloat() / targetSampleRate
        val newLength = (audioData.size / ratio).toInt()
        
        return FloatArray(newLength) { i ->
            val originalIndex = i * ratio
            val index1 = originalIndex.toInt()
            val index2 = minOf(index1 + 1, audioData.size - 1)
            val fraction = originalIndex - index1
            
            // Linear interpolation
            audioData[index1] * (1 - fraction) + audioData[index2] * fraction
        }
    }
    
    /**
     * Convert stereo to mono by averaging channels
     */
    private fun convertStereoToMono(stereoData: FloatArray): FloatArray {
        return FloatArray(stereoData.size / 2) { i ->
            (stereoData[i * 2] + stereoData[i * 2 + 1]) / 2f
        }
    }
    
    /**
     * Normalize audio to [-1, 1] range
     */
    private fun normalizeAudio(audioData: FloatArray): FloatArray {
        val maxAbs = audioData.maxOfOrNull { abs(it) } ?: 1.0f
        
        return if (maxAbs > 0) {
            FloatArray(audioData.size) { i ->
                audioData[i] / maxAbs
            }
        } else {
            audioData
        }
    }
    
    private data class WavHeader(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int
    )
}
