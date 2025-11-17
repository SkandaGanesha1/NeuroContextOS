package com.cortexn.app.services

import android.app.Service
import android.content.Intent
import android.os.BatteryManager
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File

/**
 * Power Profiler Service
 * 
 * Monitors power consumption and generates telemetry:
 * - Battery stats
 * - Power rails (CPU, GPU, NPU, DRAM)
 * - Thermal state
 * - Energy per inference
 */
class PowerProfilerService : Service() {
    
    private val binder = ProfilerBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private lateinit var batteryManager: BatteryManager
    
    private val _powerMetrics = MutableStateFlow(PowerMetrics())
    val powerMetrics: StateFlow<PowerMetrics> = _powerMetrics.asStateFlow()
    
    data class PowerMetrics(
        val currentPowerWatts: Float = 0f,
        val energyJoules: Float = 0f,
        val thermalState: String = "NONE",
        val powerRails: Map<String, Float> = emptyMap()
    )
    
    inner class ProfilerBinder : Binder() {
        fun getService(): PowerProfilerService = this@PowerProfilerService
    }
    
    override fun onCreate() {
        super.onCreate()
        Timber.i("PowerProfilerService created")
        
        batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        
        // Start monitoring
        scope.launch {
            monitorPower()
        }
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    private suspend fun monitorPower() {
        while (isActive) {
            try {
                val metrics = collectMetrics()
                _powerMetrics.value = metrics
                
                delay(1000)  // Update every second
                
            } catch (e: Exception) {
                Timber.e(e, "Power monitoring error")
            }
        }
    }
    
    private fun collectMetrics(): PowerMetrics {
        // Get instant power (microWatts)
        val powerMicroWatts = batteryManager.getIntProperty(
            BatteryManager.BATTERY_PROPERTY_CURRENT_NOW
        )
        val powerWatts = powerMicroWatts / 1_000_000f
        
        // Read power rails from sysfs (device-specific)
        val powerRails = readPowerRails()
        
        // Get thermal state
        val thermalState = getThermalState()
        
        return PowerMetrics(
            currentPowerWatts = powerWatts,
            thermalState = thermalState,
            powerRails = powerRails
        )
    }
    
    private fun readPowerRails(): Map<String, Float> {
        val rails = mutableMapOf<String, Float>()
        
        // Read from ODPM (On-Device Power Monitor) - Pixel devices
        val odpmPath = "/sys/bus/iio/devices/iio:device0"
        
        try {
            listOf("cpu", "gpu", "npu", "dram").forEach { rail ->
                val file = File("$odpmPath/energy_$rail")
                if (file.exists()) {
                    val energy = file.readText().trim().toFloatOrNull() ?: 0f
                    rails[rail] = energy / 1_000_000f  // Convert to Watts
                }
            }
        } catch (e: Exception) {
            Timber.w("Failed to read power rails: ${e.message}")
        }
        
        return rails
    }
    
    private fun getThermalState(): String {
        return try {
            val file = File("/sys/class/thermal/thermal_zone0/temp")
            if (file.exists()) {
                val temp = file.readText().trim().toInt() / 1000
                when {
                    temp < 40 -> "NONE"
                    temp < 50 -> "LIGHT"
                    temp < 60 -> "MODERATE"
                    temp < 70 -> "SEVERE"
                    else -> "CRITICAL"
                }
            } else {
                "UNKNOWN"
            }
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Timber.i("PowerProfilerService destroyed")
    }
}
