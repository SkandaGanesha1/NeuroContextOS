package com.cortexn.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cortexn.aura.AURARouter
import com.cortexn.aura.EpsilonGreedyPolicy
import com.cortexn.aura.TaskSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * AURA Router Foreground Service
 * 
 * Exposes TaskSpec API for:
 * - Image classification (YOLO)
 * - Audio transcription (Whisper)
 * - Text generation (Llama)
 * - Audio generation (Stable Audio)
 * 
 * Features:
 * - Adaptive backend selection (ONNX RT, ExecuTorch, NNAPI)
 * - QoS-aware scheduling
 * - Power/thermal management
 * - Real-time telemetry
 */
class AURARouterService : Service() {
    
    private val binder = AURARouterBinder()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private lateinit var auraRouter: AURARouter
    
    private val _routerState = MutableStateFlow(RouterState())
    val routerState: StateFlow<RouterState> = _routerState.asStateFlow()
    
    data class RouterState(
        val isInitialized: Boolean = false,
        val availableBackends: List<String> = emptyList(),
        val tasksCompleted: Int = 0,
        val avgLatencyMs: Float = 0f,
        val totalEnergyJoules: Float = 0f
    )
    
    inner class AURARouterBinder : Binder() {
        fun getService(): AURARouterService = this@AURARouterService
    }
    
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "aura_router_channel"
        private const val CHANNEL_NAME = "AURA Router"
    }
    
    override fun onCreate() {
        super.onCreate()
        Timber.i("AURARouterService created")
        
        // Create notification channel
        createNotificationChannel()
        
        // Start foreground
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
        
        // Initialize AURA Router
        scope.launch {
            try {
                auraRouter = AURARouter(
                    context = applicationContext,
                    policy = EpsilonGreedyPolicy(epsilon = 0.1f)
                )
                
                _routerState.value = _routerState.value.copy(
                    isInitialized = true,
                    availableBackends = listOf("ONNX RT", "ExecuTorch", "NNAPI")
                )
                
                updateNotification("Ready")
                Timber.i("✓ AURA Router initialized")
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize AURA Router")
                updateNotification("Error")
            }
        }
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    /**
     * Schedule a task on AURA Router
     */
    suspend fun scheduleTask(spec: TaskSpec): AURARouter.ScheduleResult {
        if (!_routerState.value.isInitialized) {
            throw IllegalStateException("AURA Router not initialized")
        }
        
        return withContext(Dispatchers.Default) {
            val result = auraRouter.schedule(spec)
            
            // Update state
            _routerState.value = _routerState.value.copy(
                tasksCompleted = _routerState.value.tasksCompleted + 1,
                avgLatencyMs = result.metrics.latencyMs,
                totalEnergyJoules = _routerState.value.totalEnergyJoules + result.metrics.energyJoules
            )
            
            result
        }
    }
    
    /**
     * Get router statistics
     */
    fun getStats(): AURARouter.RouterState {
        return auraRouter.routerState.value
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AURA ML inference router"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AURA Router")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        
        if (::auraRouter.isInitialized) {
            auraRouter.shutdown()
        }
        
        Timber.i("AURARouterService destroyed")
    }
}
