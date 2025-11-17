package com.cortexn.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cortexn.app.services.AURARouterService
import com.cortexn.app.services.PowerProfilerService
import com.cortexn.app.ui.theme.CortexNTheme
import com.cortexn.app.util.CpuFeatures
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Main Activity - Entry point for Cortex-N × SAPIENT × AURA showcase app
 * 
 * Initializes:
 * - AURA Runtime (adaptive ML routing)
 * - Power profiling services
 * - Federated learning components
 * - Navigation graph
 */
class MainActivity : ComponentActivity() {
    
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BODY_SENSORS
        )
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Timber.i("All permissions granted")
            initializeServices()
        } else {
            Timber.w("Some permissions denied: $permissions")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        Timber.i("Cortex-N × SAPIENT × AURA starting...")
        
        // Check CPU features
        logCpuFeatures()
        
        // Request permissions
        checkAndRequestPermissions()
        
        setContent {
            CortexNTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavGraph()
                }
            }
        }
    }
    
    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isEmpty()) {
            Timber.i("All permissions already granted")
            initializeServices()
        } else {
            Timber.i("Requesting permissions: $missingPermissions")
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
    
    private fun initializeServices() {
        lifecycleScope.launch {
            try {
                // Start AURA Router Service
                val auraIntent = Intent(this@MainActivity, AURARouterService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(auraIntent)
                } else {
                    startService(auraIntent)
                }
                
                // Start Power Profiler Service (if telemetry enabled)
                if (BuildConfig.ENABLE_TELEMETRY) {
                    val profilerIntent = Intent(this@MainActivity, PowerProfilerService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(profilerIntent)
                    } else {
                        startService(profilerIntent)
                    }
                }
                
                Timber.i("Services initialized successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize services")
            }
        }
    }
    
    private fun logCpuFeatures() {
        val features = CpuFeatures.detect()
        Timber.i("=== CPU Features ===")
        Timber.i("  NEON: ${features.hasNeon}")
        Timber.i("  I8MM: ${features.hasI8MM}")
        Timber.i("  SME2: ${features.hasSME2}")
        Timber.i("  Crypto: ${features.hasCrypto}")
        Timber.i("  DotProd: ${features.hasDotProd}")
        Timber.i("====================")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Stop services
        stopService(Intent(this, AURARouterService::class.java))
        stopService(Intent(this, PowerProfilerService::class.java))
        
        Timber.i("MainActivity destroyed")
    }
}
