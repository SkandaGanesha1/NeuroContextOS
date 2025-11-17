package com.cortexn.app.privacy

import com.cortexn.privacy.SecureAggregation
import timber.log.Timber

/**
 * Secure Aggregation wrapper
 * 
 * Provides privacy-preserving model aggregation
 */
class SecAgg(
    private val nodeId: String,
    threshold: Int = 3
) {
    private val secAgg = SecureAggregation(nodeId, threshold)
    
    /**
     * Generate public key for DH exchange
     */
    fun generatePublicKey(): ByteArray {
        return secAgg.generatePublicKey()
    }
    
    /**
     * Establish pairwise key with peer
     */
    fun establishPairwiseKey(peerId: String, peerPublicKey: ByteArray): ByteArray {
        return secAgg.establishPairwiseKey(peerId, peerPublicKey)
    }
    
    /**
     * Create masked model update
     */
    fun createMaskedUpdate(
        localUpdate: Map<String, FloatArray>,
        peerIds: List<String>
    ): Map<String, FloatArray> {
        return secAgg.createMaskedUpdate(localUpdate, peerIds)
    }
    
    /**
     * Aggregate masked updates
     */
    fun aggregateMaskedUpdates(
        maskedUpdates: List<Map<String, FloatArray>>
    ): Map<String, FloatArray> {
        return secAgg.aggregateMaskedUpdates(maskedUpdates)
    }
    
    /**
     * Cleanup sensitive data
     */
    fun cleanup() {
        secAgg.cleanup()
    }
}
