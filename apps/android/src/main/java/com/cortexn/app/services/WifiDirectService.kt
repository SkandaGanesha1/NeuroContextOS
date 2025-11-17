package com.cortexn.app.services

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.cortexn.privacy.WifiDirectManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * WiFi Direct Service for P2P Federated Learning
 */
class WifiDirectService : Service() {
    
    private val binder = WifiDirectBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private lateinit var wifiDirectManager: WifiDirectManager
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    enum class ConnectionState {
        DISCONNECTED,
        DISCOVERING,
        CONNECTING,
        CONNECTED
    }
    
    inner class WifiDirectBinder : Binder() {
        fun getService(): WifiDirectService = this@WifiDirectService
    }
    
    override fun onCreate() {
        super.onCreate()
        Timber.i("WifiDirectService created")
        
        wifiDirectManager = WifiDirectManager(applicationContext)
        wifiDirectManager.initialize()
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    fun discoverPeers() {
        _connectionState.value = ConnectionState.DISCOVERING
        
        wifiDirectManager.discoverPeers { peers ->
            Timber.i("Discovered ${peers.size} peers")
            _connectionState.value = if (peers.isNotEmpty()) {
                ConnectionState.CONNECTED
            } else {
                ConnectionState.DISCONNECTED
            }
        }
    }
    
    fun getWifiDirectManager(): WifiDirectManager {
        return wifiDirectManager
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        wifiDirectManager.shutdown()
        Timber.i("WifiDirectService destroyed")
    }
}
