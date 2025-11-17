package com.cortexn.privacy

import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Secure Aggregation for Federated Learning
 * 
 * Implements privacy-preserving model aggregation using:
 * - Shamir's Secret Sharing for dropout resilience
 * - Pairwise masking with DH key exchange
 * - Aggregation without revealing individual updates
 * 
 * Based on "Practical Secure Aggregation for Privacy-Preserving Machine Learning"
 * (Bonawitz et al., 2017)
 */
class SecureAggregation(
    private val nodeId: String,
    private val threshold: Int = 3  // Minimum nodes for reconstruction
) {
    private val random = SecureRandom()
    
    // Pairwise keys for masking
    private val pairwiseKeys = mutableMapOf<String, ByteArray>()
    
    // Shares for secret sharing
    private val shares = mutableMapOf<String, Map<Int, FloatArray>>()
    
    companion object {
        private const val PRIME = 2147483647L  // Large prime for field arithmetic
        private const val AES_KEY_SIZE = 32    // 256-bit AES
    }
    
    /**
     * Round 0: Advertise public keys for DH key exchange
     */
    fun generatePublicKey(): ByteArray {
        // Generate ephemeral DH key pair
        val keyPairGen = java.security.KeyPairGenerator.getInstance("DH")
        keyPairGen.initialize(2048)
        val keyPair = keyPairGen.generateKeyPair()
        
        return keyPair.public.encoded
    }
    
    /**
     * Round 1: Establish pairwise keys with all peers
     */
    fun establishPairwiseKey(peerId: String, peerPublicKey: ByteArray): ByteArray {
        // Generate local key pair
        val keyPairGen = java.security.KeyPairGenerator.getInstance("DH")
        keyPairGen.initialize(2048)
        val keyPair = keyPairGen.generateKeyPair()
        
        // Perform DH key exchange
        val keyFactory = java.security.KeyFactory.getInstance("DH")
        val peerKey = keyFactory.generatePublic(
            java.security.spec.X509EncodedKeySpec(peerPublicKey)
        )
        
        val keyAgreement = KeyAgreement.getInstance("DH")
        keyAgreement.init(keyPair.private)
        keyAgreement.doPhase(peerKey, true)
        
        val sharedSecret = keyAgreement.generateSecret()
        
        // Derive symmetric key using HKDF
        val pairwiseKey = hkdf(sharedSecret, nodeId + peerId, AES_KEY_SIZE)
        pairwiseKeys[peerId] = pairwiseKey
        
        Timber.d("Established pairwise key with $peerId")
        
        return keyPair.public.encoded
    }
    
    /**
     * Round 2: Share masked model update
     * 
     * Masked update = local_update + sum(pairwise_masks)
     */
    fun createMaskedUpdate(
        localUpdate: Map<String, FloatArray>,
        peerIds: List<String>
    ): Map<String, FloatArray> {
        val masked = mutableMapOf<String, FloatArray>()
        
        localUpdate.forEach { (key, weights) ->
            val maskedWeights = weights.copyOf()
            
            // Add pairwise masks
            peerIds.forEach { peerId ->
                val pairwiseKey = pairwiseKeys[peerId]
                if (pairwiseKey != null) {
                    val mask = generatePairwiseMask(peerId, key, weights.size, pairwiseKey)
                    
                    // Determine sign based on node ordering
                    val sign = if (nodeId < peerId) 1.0f else -1.0f
                    
                    for (i in maskedWeights.indices) {
                        maskedWeights[i] += sign * mask[i]
                    }
                }
            }
            
            masked[key] = maskedWeights
        }
        
        return masked
    }
    
    /**
     * Generate deterministic pairwise mask from shared key
     */
    private fun generatePairwiseMask(
        peerId: String,
        layerKey: String,
        size: Int,
        pairwiseKey: ByteArray
    ): FloatArray {
        // Use AES-CTR for deterministic pseudorandom generation
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val secretKey = SecretKeySpec(pairwiseKey, "AES")
        
        // IV derived from layer key
        val iv = MessageDigest.getInstance("SHA-256")
            .digest(layerKey.toByteArray())
            .copyOf(16)
        
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        
        // Generate random bytes
        val randomBytes = ByteArray(size * 4)
        cipher.doFinal(ByteArray(size * 4), 0, size * 4, randomBytes)
        
        // Convert to floats
        val mask = FloatArray(size)
        val buffer = java.nio.ByteBuffer.wrap(randomBytes)
        for (i in 0 until size) {
            mask[i] = buffer.float
        }
        
        return mask
    }
    
    /**
     * Round 3: Aggregate masked updates from all peers
     * 
     * The pairwise masks cancel out, revealing only the sum
     */
    fun aggregateMaskedUpdates(
        maskedUpdates: List<Map<String, FloatArray>>
    ): Map<String, FloatArray> {
        if (maskedUpdates.isEmpty()) {
            return emptyMap()
        }
        
        val aggregated = mutableMapOf<String, FloatArray>()
        val firstUpdate = maskedUpdates.first()
        
        firstUpdate.keys.forEach { key ->
            val size = firstUpdate[key]!!.size
            val summedWeights = FloatArray(size)
            
            maskedUpdates.forEach { update ->
                val weights = update[key] ?: return@forEach
                for (i in summedWeights.indices) {
                    summedWeights[i] += weights[i]
                }
            }
            
            // Average
            for (i in summedWeights.indices) {
                summedWeights[i] /= maskedUpdates.size
            }
            
            aggregated[key] = summedWeights
        }
        
        Timber.i("Aggregated ${maskedUpdates.size} masked updates")
        return aggregated
    }
    
    /**
     * Shamir's Secret Sharing for dropout resilience
     * Split weights into shares that can be reconstructed with threshold
     */
    fun createShares(
        weights: Map<String, FloatArray>,
        numShares: Int
    ): List<Map<String, FloatArray>> {
        require(numShares >= threshold) { "numShares must be >= threshold" }
        
        val shares = mutableListOf<Map<String, FloatArray>>()
        
        repeat(numShares) { shareIdx ->
            val share = mutableMapOf<String, FloatArray>()
            
            weights.forEach { (key, values) ->
                val shareValues = FloatArray(values.size)
                
                for (i in values.indices) {
                    // Create polynomial: f(x) = a0 + a1*x + a2*x^2 + ...
                    val coeffs = FloatArray(threshold)
                    coeffs[0] = values[i]  // Secret is the constant term
                    
                    for (j in 1 until threshold) {
                        coeffs[j] = random.nextFloat() * 2 - 1  // Random coefficients
                    }
                    
                    // Evaluate polynomial at x = shareIdx + 1
                    val x = (shareIdx + 1).toFloat()
                    var y = 0.0f
                    var xPower = 1.0f
                    
                    for (coeff in coeffs) {
                        y += coeff * xPower
                        xPower *= x
                    }
                    
                    shareValues[i] = y
                }
                
                share[key] = shareValues
            }
            
            shares.add(share)
        }
        
        return shares
    }
    
    /**
     * Reconstruct weights from shares using Lagrange interpolation
     */
    fun reconstructFromShares(
        shares: List<Pair<Int, Map<String, FloatArray>>>
    ): Map<String, FloatArray> {
        require(shares.size >= threshold) { "Not enough shares for reconstruction" }
        
        val reconstructed = mutableMapOf<String, FloatArray>()
        val firstShare = shares.first().second
        
        firstShare.keys.forEach { key ->
            val size = firstShare[key]!!.size
            val values = FloatArray(size)
            
            for (i in 0 until size) {
                // Lagrange interpolation at x = 0
                var sum = 0.0f
                
                shares.forEach { (shareIdx, shareData) ->
                    val shareValue = shareData[key]!![i]
                    val x_j = (shareIdx + 1).toFloat()
                    
                    // Calculate Lagrange basis polynomial at x = 0
                    var basis = 1.0f
                    shares.forEach { (otherIdx, _) ->
                        if (otherIdx != shareIdx) {
                            val x_m = (otherIdx + 1).toFloat()
                            basis *= (0 - x_m) / (x_j - x_m)
                        }
                    }
                    
                    sum += shareValue * basis
                }
                
                values[i] = sum
            }
            
            reconstructed[key] = values
        }
        
        Timber.i("Reconstructed weights from ${shares.size} shares")
        return reconstructed
    }
    
    /**
     * HKDF (HMAC-based Key Derivation Function)
     */
    private fun hkdf(ikm: ByteArray, info: String, length: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val salt = ByteArray(32) // Zero salt
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var i = 1
        
        while (offset < length) {
            mac.update(t)
            mac.update(info.toByteArray())
            mac.update(i.toByte())
            t = mac.doFinal()
            
            val remaining = length - offset
            val toCopy = minOf(t.size, remaining)
            System.arraycopy(t, 0, okm, offset, toCopy)
            offset += toCopy
            i++
        }
        
        return okm
    }
    
    /**
     * Clear sensitive data
     */
    fun cleanup() {
        pairwiseKeys.clear()
        shares.clear()
        Timber.d("Secure aggregation cleanup complete")
    }
}
