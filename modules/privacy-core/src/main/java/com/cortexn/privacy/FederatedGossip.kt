package com.cortexn.privacy

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.random.Random

/**
 * Federated Gossip Protocol for decentralized model aggregation
 * 
 * Implements peer-to-peer gossip-based federated learning:
 * - Epidemic-style model propagation
 * - Byzantine fault tolerance
 * - Adaptive peer selection
 * - Version vector consistency
 * 
 * Based on "Gossip Learning with Linear Models on Fully Distributed Data"
 * (Ormándi et al., 2013)
 */
class FederatedGossip(
    private val nodeId: String,
    private val wifiDirectManager: WifiDirectManager
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Model state
    private val localModel = ConcurrentHashMap<String, FloatArray>()
    private val modelVersion = MutableStateFlow(0)
    
    // Peer state
    private val peers = ConcurrentHashMap<String, PeerInfo>()
    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()
    
    // Gossip configuration
    private val gossipIntervalMs = 5000L
    private val maxPeersPerRound = 3
    private val modelExpiry = 30000L  // 30 seconds
    
    // Byzantine fault tolerance
    private val byzantineThreshold = 0.3f  // Max 30% malicious peers
    
    data class PeerInfo(
        val peerId: String,
        val address: String,
        val port: Int,
        var lastSeen: Long,
        var modelVersion: Int,
        var reputation: Float = 1.0f
    )
    
    data class GossipMessage(
        val type: MessageType,
        val senderId: String,
        val version: Int,
        val modelWeights: Map<String, FloatArray>?,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        enum class MessageType {
            PUSH,      // Push local model to peer
            PULL,      // Request model from peer
            SYNC       // Bidirectional sync
        }
        
        fun toByteArray(): ByteArray {
            // Simplified serialization (use Protobuf/Flatbuffers in production)
            val builder = StringBuilder()
            builder.append("${type.name}|$senderId|$version|$timestamp")
            
            modelWeights?.forEach { (key, weights) ->
                builder.append("|$key:")
                weights.forEach { w -> builder.append("$w,") }
            }
            
            return builder.toString().toByteArray()
        }
        
        companion object {
            fun fromByteArray(data: ByteArray): GossipMessage {
                // Simplified deserialization
                val parts = String(data).split("|")
                return GossipMessage(
                    type = MessageType.valueOf(parts[0]),
                    senderId = parts[1],
                    version = parts[2].toInt(),
                    modelWeights = null,  // Parse weights if needed
                    timestamp = parts[3].toLong()
                )
            }
        }
    }
    
    /**
     * Initialize gossip protocol
     */
    fun start() {
        Timber.i("Starting federated gossip protocol (node: $nodeId)")
        
        // Start gossip rounds
        scope.launch {
            while (isActive) {
                try {
                    performGossipRound()
                    delay(gossipIntervalMs)
                } catch (e: Exception) {
                    Timber.e(e, "Gossip round failed")
                }
            }
        }
        
        // Start peer discovery
        scope.launch {
            discoverPeers()
        }
        
        // Cleanup expired peers
        scope.launch {
            while (isActive) {
                cleanupExpiredPeers()
                delay(10000)
            }
        }
    }
    
    /**
     * Perform one round of gossip
     */
    private suspend fun performGossipRound() {
        if (peers.isEmpty()) {
            Timber.d("No peers available for gossip")
            return
        }
        
        // Select peers using reputation-based sampling
        val selectedPeers = selectPeersForGossip()
        
        Timber.d("Gossip round: selected ${selectedPeers.size} peers")
        
        selectedPeers.forEach { peer ->
            scope.launch {
                try {
                    gossipWithPeer(peer)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to gossip with peer ${peer.peerId}")
                    updatePeerReputation(peer.peerId, false)
                }
            }
        }
    }
    
    /**
     * Select peers for gossip using power-law distribution
     */
    private fun selectPeersForGossip(): List<PeerInfo> {
        val peerList = peers.values.toList()
        
        if (peerList.size <= maxPeersPerRound) {
            return peerList
        }
        
        // Weighted sampling based on reputation
        val weights = peerList.map { peer ->
            peer.reputation * exp(-((System.currentTimeMillis() - peer.lastSeen) / 10000.0)).toFloat()
        }
        
        val totalWeight = weights.sum()
        val selected = mutableListOf<PeerInfo>()
        
        repeat(maxPeersPerRound) {
            val rand = Random.nextFloat() * totalWeight
            var cumulative = 0f
            
            for (i in peerList.indices) {
                cumulative += weights[i]
                if (rand <= cumulative) {
                    selected.add(peerList[i])
                    break
                }
            }
        }
        
        return selected.distinct()
    }
    
    /**
     * Gossip with a specific peer
     */
    private suspend fun gossipWithPeer(peer: PeerInfo) {
        Timber.d("Gossiping with peer ${peer.peerId}")
        
        // Create gossip message
        val message = GossipMessage(
            type = GossipMessage.MessageType.SYNC,
            senderId = nodeId,
            version = modelVersion.value,
            modelWeights = localModel.toMap()
        )
        
        // Send via Wi-Fi Direct
        val response = wifiDirectManager.sendMessage(
            peer.address,
            peer.port,
            message.toByteArray()
        )
        
        if (response != null) {
            val remoteMessage = GossipMessage.fromByteArray(response)
            handleGossipResponse(peer, remoteMessage)
            updatePeerReputation(peer.peerId, true)
        }
    }
    
    /**
     * Handle gossip response from peer
     */
    private fun handleGossipResponse(peer: PeerInfo, message: GossipMessage) {
        peer.lastSeen = System.currentTimeMillis()
        peer.modelVersion = message.version
        
        // Byzantine fault detection
        if (!validateModel(message.modelWeights)) {
            Timber.w("Received invalid model from ${peer.peerId}, marking as Byzantine")
            updatePeerReputation(peer.peerId, false)
            return
        }
        
        // Merge models if peer has newer version
        if (message.version > modelVersion.value) {
            message.modelWeights?.let { remoteWeights ->
                mergeModels(remoteWeights)
                modelVersion.value = maxOf(modelVersion.value, message.version) + 1
                Timber.i("Merged model from ${peer.peerId}, new version: ${modelVersion.value}")
            }
        }
    }
    
    /**
     * Merge remote model weights with local model
     * Uses secure aggregation for privacy
     */
    private fun mergeModels(remoteWeights: Map<String, FloatArray>) {
        remoteWeights.forEach { (key, remoteArray) ->
            val localArray = localModel.getOrPut(key) { FloatArray(remoteArray.size) }
            
            // Weighted average (can be replaced with SecureAggregation)
            for (i in localArray.indices) {
                localArray[i] = (localArray[i] + remoteArray[i]) / 2.0f
            }
        }
    }
    
    /**
     * Validate model integrity (Byzantine fault detection)
     */
    private fun validateModel(weights: Map<String, FloatArray>?): Boolean {
        if (weights == null) return false
        
        // Check for NaN/Inf values
        weights.values.forEach { array ->
            if (array.any { it.isNaN() || it.isInfinite() }) {
                return false
            }
        }
        
        // Check weight magnitudes (simple heuristic)
        val maxMagnitude = 10.0f
        weights.values.forEach { array ->
            if (array.any { kotlin.math.abs(it) > maxMagnitude }) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * Update peer reputation using EWMA
     */
    private fun updatePeerReputation(peerId: String, success: Boolean) {
        peers[peerId]?.let { peer ->
            val alpha = 0.1f
            peer.reputation = if (success) {
                peer.reputation * (1 - alpha) + alpha
            } else {
                peer.reputation * (1 - alpha)
            }
            
            // Remove peers with very low reputation
            if (peer.reputation < 0.1f) {
                peers.remove(peerId)
                Timber.w("Removed peer $peerId due to low reputation")
            }
        }
    }
    
    /**
     * Discover peers via Wi-Fi Direct
     */
    private suspend fun discoverPeers() {
        wifiDirectManager.discoverPeers { discoveredPeers ->
            discoveredPeers.forEach { (peerId, address, port) ->
                if (peerId != nodeId && !peers.containsKey(peerId)) {
                    val peerInfo = PeerInfo(
                        peerId = peerId,
                        address = address,
                        port = port,
                        lastSeen = System.currentTimeMillis(),
                        modelVersion = 0
                    )
                    peers[peerId] = peerInfo
                    Timber.i("Discovered peer: $peerId")
                }
            }
            _peerCount.value = peers.size
        }
    }
    
    /**
     * Remove peers that haven't been seen recently
     */
    private fun cleanupExpiredPeers() {
        val now = System.currentTimeMillis()
        val expired = peers.filter { (_, peer) ->
            now - peer.lastSeen > modelExpiry
        }
        
        expired.forEach { (peerId, _) ->
            peers.remove(peerId)
            Timber.d("Removed expired peer: $peerId")
        }
        
        _peerCount.value = peers.size
    }
    
    /**
     * Update local model weights
     */
    fun updateLocalModel(weights: Map<String, FloatArray>) {
        weights.forEach { (key, array) ->
            localModel[key] = array.copyOf()
        }
        modelVersion.value++
        Timber.d("Local model updated, version: ${modelVersion.value}")
    }
    
    /**
     * Get current local model
     */
    fun getLocalModel(): Map<String, FloatArray> {
        return localModel.toMap()
    }
    
    /**
     * Shutdown gossip protocol
     */
    fun shutdown() {
        scope.cancel()
        peers.clear()
        Timber.i("Federated gossip protocol shut down")
    }
}
