package com.cortexn.audiogen

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Foreground service for audio generation and playback
 * 
 * Handles:
 * - Background audio generation
 * - Real-time audio playback via AudioTrack
 * - Progress notifications
 * - Lifecycle management
 */
class AudioGenService : Service() {
    
    private val binder = AudioGenBinder()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private lateinit var audioGen: AudioGen
    private var audioTrack: AudioTrack? = null
    private var currentJob: Job? = null
    
    private val _state = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val state: StateFlow<ServiceState> = _state.asStateFlow()
    
    sealed class ServiceState {
        object Idle : ServiceState()
        data class Generating(val progress: Int, val total: Int, val status: String) : ServiceState()
        data class Playing(val position: Int, val duration: Int) : ServiceState()
        data class Error(val message: String) : ServiceState()
    }
    
    inner class AudioGenBinder : Binder() {
        fun getService(): AudioGenService = this@AudioGenService
    }
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audiogen_channel"
        private const val CHANNEL_NAME = "Audio Generation"
        
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE_MULTIPLIER = 2
    }
    
    override fun onCreate() {
        super.onCreate()
        Timber.i("AudioGenService created")
        
        // Initialize AudioGen
        audioGen = AudioGen(this)
        val config = AudioGen.Config(
            modelDir = audioGen.getDefaultModelDir(),
            useGpu = false,
            numThreads = 4
        )
        
        if (!audioGen.initialize(config)) {
            Timber.e("Failed to initialize AudioGen")
            stopSelf()
            return
        }
        
        // Create notification channel
        createNotificationChannel()
        
        // Start foreground
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Timber.i("AudioGenService destroyed")
        
        currentJob?.cancel()
        scope.cancel()
        
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        
        audioGen.release()
    }
    
    /**
     * Generate and play audio from prompt
     */
    fun generateAndPlay(params: AudioGen.GenerationParams) {
        // Cancel existing job
        currentJob?.cancel()
        
        currentJob = scope.launch {
            try {
                _state.value = ServiceState.Generating(0, params.numInferenceSteps, "Starting...")
                
                // Update notification
                updateNotification("Generating: ${params.prompt}")
                
                // Generate audio with progress
                val audio = audioGen.generateWithProgress(params) { current, total, status ->
                    _state.value = ServiceState.Generating(current, total, status)
                    updateNotification("Generating: $current/$total - $status")
                }
                
                if (audio == null) {
                    _state.value = ServiceState.Error("Generation failed")
                    updateNotification("Generation failed")
                    return@launch
                }
                
                // Play audio
                playAudio(audio)
                
            } catch (e: CancellationException) {
                Timber.i("Generation cancelled")
                _state.value = ServiceState.Idle
                updateNotification("Cancelled")
            } catch (e: Exception) {
                Timber.e(e, "Generation error")
                _state.value = ServiceState.Error(e.message ?: "Unknown error")
                updateNotification("Error: ${e.message}")
            }
        }
    }
    
    /**
     * Play generated audio via AudioTrack
     */
    private suspend fun playAudio(audioData: FloatArray) = withContext(Dispatchers.IO) {
        Timber.i("Playing audio: ${audioData.size} samples")
        
        updateNotification("Playing audio...")
        
        // Initialize AudioTrack
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        
        val bufferSize = minBufferSize * BUFFER_SIZE_MULTIPLIER
        
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        
        audioTrack?.play()
        
        // Stream audio in chunks
        val chunkSize = 4096
        var position = 0
        
        while (position < audioData.size && isActive) {
            val remaining = audioData.size - position
            val count = minOf(chunkSize, remaining)
            
            val written = audioTrack?.write(
                audioData,
                position,
                count,
                AudioTrack.WRITE_BLOCKING
            ) ?: 0
            
            if (written < 0) {
                Timber.e("AudioTrack write error: $written")
                break
            }
            
            position += written
            
            // Update state
            _state.value = ServiceState.Playing(position, audioData.size)
            updateNotification("Playing: ${position * 100 / audioData.size}%")
            
            delay(10) // Small delay to prevent busy-waiting
        }
        
        // Wait for playback to finish
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        
        _state.value = ServiceState.Idle
        updateNotification("Playback complete")
        
        Timber.i("Playback complete")
    }
    
    /**
     * Cancel current operation
     */
    fun cancel() {
        audioGen.cancel()
        currentJob?.cancel()
        audioTrack?.stop()
        
        _state.value = ServiceState.Idle
        updateNotification("Cancelled")
    }
    
    /**
     * Create notification channel (Android O+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Audio generation notifications"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Create notification
     */
    private fun createNotification(contentText: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioGen")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    /**
     * Update notification text
     */
    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
