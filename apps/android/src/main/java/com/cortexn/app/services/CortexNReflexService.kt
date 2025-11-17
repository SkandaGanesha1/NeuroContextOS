package com.cortexn.app.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.IBinder
import com.cortexn.snn.CortexNSNN
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Cortex-N Reflex Service - IMU → SNN gesture recognition
 * 
 * Captures accelerometer/gyroscope data and runs
 * ultra-low-power SNN inference for gesture detection
 */
class CortexNReflexService : Service(), SensorEventListener {
    
    private val binder = ReflexBinder()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    
    private lateinit var snnEngine: CortexNSNN
    
    private val _gestureState = MutableStateFlow<GestureState>(GestureState.Idle)
    val gestureState: StateFlow<GestureState> = _gestureState.asStateFlow()
    
    private val imuBuffer = mutableListOf<FloatArray>()
    private val bufferSize = 100  // 1 second at 100Hz
    
    sealed class GestureState {
        object Idle : GestureState()
        data class Detected(val gesture: String, val confidence: Float) : GestureState()
    }
    
    inner class ReflexBinder : Binder() {
        fun getService(): CortexNReflexService = this@CortexNReflexService
    }
    
    override fun onCreate() {
        super.onCreate()
        Timber.i("CortexNReflexService created")
        
        // Initialize SNN
        snnEngine = CortexNSNN(applicationContext)
        
        scope.launch {
            if (!snnEngine.initialize()) {
                Timber.e("Failed to initialize SNN")
            }
        }
        
        // Initialize sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    fun startDetection() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        
        Timber.i("Gesture detection started")
    }
    
    fun stopDetection() {
        sensorManager.unregisterListener(this)
        imuBuffer.clear()
        _gestureState.value = GestureState.Idle
        
        Timber.i("Gesture detection stopped")
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val data = floatArrayOf(event.values[0], event.values[1], event.values[2])
                imuBuffer.add(data)
                
                if (imuBuffer.size >= bufferSize) {
                    processGesture()
                    imuBuffer.removeAt(0)  // Sliding window
                }
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }
    
    private fun processGesture() {
        scope.launch {
            try {
                // Convert buffer to SNN input
                val input = imuBuffer.flatten().toFloatArray()
                
                // Run SNN inference
                val result = snnEngine.classify(input)
                
                if (result.confidence > 0.7f) {
                    _gestureState.value = GestureState.Detected(
                        gesture = result.gesture,
                        confidence = result.confidence
                    )
                    
                    Timber.d("Gesture detected: ${result.gesture} (${result.confidence})")
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Gesture processing error")
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopDetection()
        scope.cancel()
        snnEngine.shutdown()
        
        Timber.i("CortexNReflexService destroyed")
    }
}
