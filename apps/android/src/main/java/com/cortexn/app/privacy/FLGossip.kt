package com.cortexn.app.privacy

import android.content.Context
import com.cortexn.privacy.FederatedGossip
import com.cortexn.privacy.WifiDirectManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Federated Learning Gossip Protocol
 * 
 * Manages decentralized model updates via WiFi Direct
 */
class FLGossip(private val context: Context) {
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private lateinit var gossip: FederatedGossip
    private lateinit var wifiManager: WifiDirectManager
    
    private val _flState = MutableStateFlow(FLState())
    val flState: StateFlow<FLState> = _flState.asStateFlow()
    
    data class FLState(
        val isActive: Boolean = false,
        val peerCount: Int = 0,
        val modelVersion: Int = 0,
        val lastUpdate: Long = 0
    )
    
    fun initialize() {
        try {
            Timber.i("Initializing FL Gossip...")
            
            wifiManager = WifiDirectManager(context)
            wifiManager.initialize()
            
            val nodeId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            
            gossip = FederatedGossip(nodeId, wifiManager)
            gossip.start()
            
            // Observe peer count
            scope.launch {
                gossip.peerCount.collect { count ->
                    _flState.value = _flState.value.copy(
                        peerCount = count,
                        isActive = count > 0
                    )
                }
            }
            
            Timber.i("✓ FL Gossip initialized")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize FL Gossip")
        }
    }
    
    fun updateModel(weights: Map<String, FloatArray>) {
        gossip.updateLocalModel(weights)
        
        _flState.value = _flState.value.copy(
            modelVersion = _flState.value.modelVersion + 1,
            lastUpdate = System.currentTimeMillis()
        )
    }
    
    fun getModel(): Map<String, FloatArray> {
        return gossip.getLocalModel()
    }
    
    fun shutdown() {
        scope.cancel()
        gossip.shutdown()
        wifiManager.shutdown()
    }
}
