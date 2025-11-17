package com.cortexn.aura.telemetry

import android.content.Context
import android.os.BatteryManager
import timber.log.Timber
import java.io.File

/**
 * Power rails sampler for on-device power measurement
 * 
 * Samples power from:
 * - Battery stats (voltage, current)
 * - ODPM (On-Device Power Monitor) if available
 * - Sysfs power supply entries
 * 
 * Provides per-component power breakdown (CPU, GPU, DRAM, etc.)
 */
class RailsSampler(private val context: Context) {
    
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private var lastEnergyMicroJoules = 0L
    private var lastSampleTime = System.nanoTime()
    
    data class PowerRails(
        val cpuPowerWatts: Float,
        val gpuPowerWatts: Float,
        val dramPowerWatts: Float,
        val systemPowerWatts: Float,
        val totalWatts: Float,
        val totalJoules: Float,
        val voltageVolts: Float,
        val currentMilliAmps: Float,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Samples all power rails
     */
    fun sampleRails(): PowerRails {
        val currentTime = System.nanoTime()
        val deltaTimeSeconds = (currentTime - lastSampleTime) / 1e9f
        
        // Battery measurements
        val voltage = getBatteryVoltage()
        val current = getBatteryCurrent()
        val power = voltage * (current / 1000f) // Convert mA to A
        
        // Estimate energy consumed since last sample
        val energyJoules = power * deltaTimeSeconds
        val totalEnergy = lastEnergyMicroJoules / 1e6f + energyJoules
        
        lastEnergyMicroJoules = (totalEnergy * 1e6).toLong()
        lastSampleTime = currentTime
        
        // Try to read per-component power if ODPM available
        val componentPower = readComponentPower()
        
        return PowerRails(
            cpuPowerWatts = componentPower?.cpuWatts ?: (power * 0.4f),
            gpuPowerWatts = componentPower?.gpuWatts ?: (power * 0.2f),
            dramPowerWatts = componentPower?.dramWatts ?: (power * 0.15f),
            systemPowerWatts = componentPower?.systemWatts ?: (power * 0.25f),
            totalWatts = power,
            totalJoules = totalEnergy,
            voltageVolts = voltage,
            currentMilliAmps = current
        )
    }
    
    /**
     * Gets battery voltage in volts
     */
    private fun getBatteryVoltage(): Float {
        return try {
            val voltageMilliVolts = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_VOLTAGE_NOW)
            voltageMilliVolts / 1000f
        } catch (e: Exception) {
            Timber.w(e, "Failed to read battery voltage")
            3.7f // Typical Li-ion voltage
        }
    }
    
    /**
     * Gets battery current in milliamps (negative = discharging)
     */
    private fun getBatteryCurrent(): Float {
        return try {
            val currentMicroAmps = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            (currentMicroAmps / 1000f).coerceAtLeast(0f) // Ensure positive
        } catch (e: Exception) {
            Timber.w(e, "Failed to read battery current")
            500f // Typical idle current
        }
    }
    
    /**
     * Reads per-component power from ODPM or sysfs
     */
    private fun readComponentPower(): ComponentPower? {
        return try {
            // Try ODPM first (device-specific paths)
            val odpmPaths = listOf(
                "/sys/class/power_supply/odpm_battery",      // Pixel
                "/sys/class/power_supply/battery/device/power_supply"  // Samsung
            )
            
            for (basePath in odpmPaths) {
                val baseDir = File(basePath)
                if (baseDir.exists()) {
                    return parseODPMPower(baseDir)
                }
            }
            
            // Fallback to generic power supply
            parseGenericPowerSupply()
        } catch (e: Exception) {
            Timber.w(e, "Failed to read component power")
            null
        }
    }
    
    private fun parseODPMPower(baseDir: File): ComponentPower? {
        try {
            val cpuPower = readSysfsFloat(File(baseDir, "cpu_power")) ?: 0f
            val gpuPower = readSysfsFloat(File(baseDir, "gpu_power")) ?: 0f
            val dramPower = readSysfsFloat(File(baseDir, "dram_power")) ?: 0f
            val systemPower = readSysfsFloat(File(baseDir, "system_power")) ?: 0f
            
            return ComponentPower(cpuPower, gpuPower, dramPower, systemPower)
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun parseGenericPowerSupply(): ComponentPower? {
        // Try to read from /sys/class/power_supply/battery/power_now
        val powerFile = File("/sys/class/power_supply/battery/power_now")
        if (powerFile.exists()) {
            val powerMicroWatts = powerFile.readText().trim().toLongOrNull() ?: return null
            val powerWatts = powerMicroWatts / 1e6f
            
            // Distribute across components (rough estimate)
            return ComponentPower(
                cpuWatts = powerWatts * 0.4f,
                gpuWatts = powerWatts * 0.2f,
                dramWatts = powerWatts * 0.15f,
                systemWatts = powerWatts * 0.25f
            )
        }
        
        return null
    }
    
    private fun readSysfsFloat(file: File): Float? {
        return try {
            if (file.exists()) {
                val value = file.readText().trim()
                // Handle different units (W, mW, µW)
                when {
                    value.endsWith("mW") -> value.dropLast(2).toFloat() / 1000f
                    value.endsWith("uW") -> value.dropLast(2).toFloat() / 1e6f
                    else -> value.toFloat()
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    fun close() {
        // Cleanup if needed
    }
    
    private data class ComponentPower(
        val cpuWatts: Float,
        val gpuWatts: Float,
        val dramWatts: Float,
        val systemWatts: Float
    )
}
