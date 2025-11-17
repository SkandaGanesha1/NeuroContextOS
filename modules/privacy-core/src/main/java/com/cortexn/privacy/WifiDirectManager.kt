package com.cortexn.privacy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.IOException
import java.net.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Wi-Fi Direct Manager for P2P communication in federated learning
 * 
 * Features:
 * - Peer discovery and connection management
 * - TCP socket-based message passing
 * - State machine for connection lifecycle
 * - Automatic reconnection on failure
 */
class WifiDirectManager(private val context: Context) {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val wifiP2pManager: WifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }
    
    private var channel: WifiP2pManager.Channel? = null
    private var serverSocket: ServerSocket? = null
    private val connections = mutableMapOf<String, Socket>()
    
    // State management
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    
    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()
    
    sealed class ConnectionState {
        object Idle : ConnectionState()
        object Discovering : ConnectionState()
        data class Connecting(val device: WifiP2pDevice) : ConnectionState()
        data class Connected(val groupInfo: WifiP2pGroup) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    companion object {
        private const val SERVER_PORT = 8888
        private const val SOCKET_TIMEOUT = 5000
        private const val MAX_RETRY_ATTEMPTS = 3
    }
    
    /**
     * Initialize Wi-Fi Direct
     */
    fun initialize() {
        channel = wifiP2pManager.initialize(context, Looper.getMainLooper(), null)
        
        // Register broadcast receiver
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        
        context.registerReceiver(wifiDirectReceiver, intentFilter)
        
        Timber.i("Wi-Fi Direct initialized")
    }
    
    /**
     * Broadcast receiver for Wi-Fi Direct events
     */
    private val wifiDirectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Timber.d("Wi-Fi P2P state changed: enabled=$enabled")
                }
                
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    channel?.let { ch ->
                        wifiP2pManager.requestPeers(ch) { peerList ->
                            _peers.value = peerList.deviceList.toList()
                            Timber.d("Peers discovered: ${peerList.deviceList.size}")
                        }
                    }
                }
                
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    channel?.let { ch ->
                        wifiP2pManager.requestConnectionInfo(ch) { info ->
                            handleConnectionInfo(info)
                        }
                    }
                }
                
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = intent.getParcelableExtra<WifiP2pDevice>(
                        WifiP2pManager.EXTRA_WIFI_P2P_DEVICE
                    )
                    Timber.d("This device changed: ${device?.deviceName}")
                }
            }
        }
    }
    
    /**
     * Handle connection info update
     */
    private fun handleConnectionInfo(info: WifiP2pInfo) {
        if (info.groupFormed) {
            if (info.isGroupOwner) {
                Timber.i("This device is group owner")
                startServer()
            } else {
                Timber.i("This device is client, connecting to ${info.groupOwnerAddress}")
                connectToGroupOwner(info.groupOwnerAddress)
            }
            
            // Request group info
            channel?.let { ch ->
                wifiP2pManager.requestGroupInfo(ch) { group ->
                    if (group != null) {
                        _state.value = ConnectionState.Connected(group)
                    }
                }
            }
        }
    }
    
    /**
     * Discover peers
     */
    fun discoverPeers(callback: (List<Triple<String, String, Int>>) -> Unit) {
        _state.value = ConnectionState.Discovering
        
        channel?.let { ch ->
            wifiP2pManager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Peer discovery initiated")
                    
                    // Wait for peers to be discovered
                    scope.launch {
                        delay(3000) // Wait for discovery to complete
                        
                        val discoveredPeers = _peers.value.map { device ->
                            Triple(device.deviceName, device.deviceAddress, SERVER_PORT)
                        }
                        
                        callback(discoveredPeers)
                    }
                }
                
                override fun onFailure(reason: Int) {
                    Timber.e("Peer discovery failed: $reason")
                    _state.value = ConnectionState.Error("Discovery failed: $reason")
                }
            })
        }
    }
    
    /**
     * Connect to a peer
     */
    suspend fun connectToPeer(device: WifiP2pDevice): Boolean = suspendCoroutine { continuation ->
        _state.value = ConnectionState.Connecting(device)
        
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        
        channel?.let { ch ->
            wifiP2pManager.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Connection initiated to ${device.deviceName}")
                    continuation.resume(true)
                }
                
                override fun onFailure(reason: Int) {
                    Timber.e("Connection failed: $reason")
                    _state.value = ConnectionState.Error("Connection failed: $reason")
                    continuation.resume(false)
                }
            })
        } ?: continuation.resume(false)
    }
    
    /**
     * Start server socket (for group owner)
     */
    private fun startServer() {
        scope.launch {
            try {
                serverSocket = ServerSocket(SERVER_PORT)
                Timber.i("Server started on port $SERVER_PORT")
                
                while (isActive) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        clientSocket?.let {
                            handleClientConnection(it)
                        }
                    } catch (e: IOException) {
                        if (serverSocket?.isClosed == false) {
                            Timber.e(e, "Error accepting connection")
                        }
                    }
                }
            } catch (e: IOException) {
                Timber.e(e, "Server socket error")
            }
        }
    }
    
    /**
     * Handle incoming client connection
     */
    private fun handleClientConnection(socket: Socket) {
        scope.launch {
            try {
                val clientAddress = socket.inetAddress.hostAddress ?: "unknown"
                connections[clientAddress] = socket
                Timber.i("Client connected: $clientAddress")
                
                // Handle incoming messages
                val inputStream = socket.getInputStream()
                val buffer = ByteArray(8192)
                
                while (isActive && !socket.isClosed) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    
                    val message = buffer.copyOf(bytesRead)
                    handleIncomingMessage(clientAddress, message)
                }
            } catch (e: IOException) {
                Timber.e(e, "Client connection error")
            }
        }
    }
    
    /**
     * Connect to group owner (for clients)
     */
    private fun connectToGroupOwner(ownerAddress: InetAddress) {
        scope.launch {
            var attempt = 0
            var connected = false
            
            while (attempt < MAX_RETRY_ATTEMPTS && !connected) {
                try {
                    val socket = Socket()
                    socket.connect(
                        InetSocketAddress(ownerAddress, SERVER_PORT),
                        SOCKET_TIMEOUT
                    )
                    
                    connections[ownerAddress.hostAddress ?: "owner"] = socket
                    connected = true
                    
                    Timber.i("Connected to group owner: ${ownerAddress.hostAddress}")
                } catch (e: IOException) {
                    attempt++
                    Timber.w("Connection attempt $attempt failed: ${e.message}")
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        delay(1000)
                    }
                }
            }
            
            if (!connected) {
                _state.value = ConnectionState.Error("Failed to connect to group owner")
            }
        }
    }
    
    /**
     * Send message to peer
     */
    suspend fun sendMessage(address: String, port: Int, message: ByteArray): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val socket = connections[address] ?: Socket().apply {
                    connect(InetSocketAddress(address, port), SOCKET_TIMEOUT)
                    connections[address] = this
                }
                
                // Send message
                socket.getOutputStream().write(message)
                socket.getOutputStream().flush()
                
                // Read response
                val inputStream = socket.getInputStream()
                val buffer = ByteArray(8192)
                val bytesRead = inputStream.read(buffer)
                
                if (bytesRead > 0) {
                    buffer.copyOf(bytesRead)
                } else {
                    null
                }
            } catch (e: IOException) {
                Timber.e(e, "Failed to send message to $address")
                connections.remove(address)
                null
            }
        }
    }
    
    /**
     * Handle incoming message
     */
    private fun handleIncomingMessage(senderAddress: String, message: ByteArray) {
        // Process message (override or use callback)
        Timber.d("Received message from $senderAddress: ${message.size} bytes")
    }
    
    /**
     * Disconnect from all peers
     */
    fun disconnect() {
        connections.values.forEach { socket ->
            try {
                socket.close()
            } catch (e: IOException) {
                Timber.w(e, "Error closing socket")
            }
        }
        connections.clear()
        
        channel?.let { ch ->
            wifiP2pManager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Group removed")
                }
                
                override fun onFailure(reason: Int) {
                    Timber.w("Failed to remove group: $reason")
                }
            })
        }
        
        _state.value = ConnectionState.Idle
    }
    
    /**
     * Shutdown Wi-Fi Direct manager
     */
    fun shutdown() {
        disconnect()
        
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Timber.w(e, "Error closing server socket")
        }
        
        context.unregisterReceiver(wifiDirectReceiver)
        scope.cancel()
        
        Timber.i("Wi-Fi Direct manager shut down")
    }
}
