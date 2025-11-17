package com.cortexn.privacy

import timber.log.Timber
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Differential Privacy mechanisms for privacy-preserving machine learning
 * 
 * Implements:
 * - Laplace mechanism for continuous values
 * - Gaussian mechanism for (ε, δ)-DP
 * - Moment Accountant for privacy budget tracking
 * - Gradient clipping for bounded sensitivity
 * 
 * Based on "Deep Learning with Differential Privacy" (Abadi et al., 2016)
 */
class DifferentialPrivacy(
    private val epsilon: Double = 1.0,      // Privacy budget
    private val delta: Double = 1e-5,       // Privacy parameter
    private val sensitivityBound: Float = 1.0f  // L2 norm bound
) {
    private val random = Random.Default
    
    // Privacy budget tracking
    private var consumedEpsilon = 0.0
    private var consumedDelta = 0.0
    
    // Moment accountant for tight privacy analysis
    private val momentOrders = listOf(1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 4.0, 5.0)
    private val moments = mutableMapOf<Double, Double>()
    
    init {
        momentOrders.forEach { moments[it] = 0.0 }
        Timber.i("Differential Privacy initialized: ε=$epsilon, δ=$delta")
    }
    
    /**
     * Add Laplace noise for ε-differential privacy
     * Noise ~ Laplace(0, Δf/ε)
     */
    fun addLaplaceNoise(value: Float, sensitivity: Float = sensitivityBound): Float {
        val scale = sensitivity / epsilon
        val noise = sampleLaplace(0.0, scale)
        return value + noise.toFloat()
    }
    
    /**
     * Add Laplace noise to array (element-wise)
     */
    fun addLaplaceNoise(values: FloatArray, sensitivity: Float = sensitivityBound): FloatArray {
        return FloatArray(values.size) { i ->
            addLaplaceNoise(values[i], sensitivity)
        }
    }
    
    /**
     * Add Gaussian noise for (ε, δ)-differential privacy
     * Noise ~ N(0, σ²) where σ = sqrt(2 ln(1.25/δ)) * Δf / ε
     */
    fun addGaussianNoise(value: Float, sensitivity: Float = sensitivityBound): Float {
        val sigma = calculateGaussianSigma(sensitivity)
        val noise = sampleGaussian(0.0, sigma)
        return value + noise.toFloat()
    }
    
    /**
     * Add Gaussian noise to array (element-wise)
     */
    fun addGaussianNoise(values: FloatArray, sensitivity: Float = sensitivityBound): FloatArray {
        val sigma = calculateGaussianSigma(sensitivity)
        return FloatArray(values.size) { i ->
            values[i] + sampleGaussian(0.0, sigma).toFloat()
        }
    }
    
    /**
     * Clip gradients to bound sensitivity (per-example clipping)
     */
    fun clipGradients(gradients: FloatArray, clipNorm: Float = sensitivityBound): FloatArray {
        val norm = l2Norm(gradients)
        
        return if (norm > clipNorm) {
            val scale = clipNorm / norm
            FloatArray(gradients.size) { i -> gradients[i] * scale }
        } else {
            gradients.copyOf()
        }
    }
    
    /**
     * Add DP noise to gradients (used in DP-SGD)
     * 
     * Steps:
     * 1. Clip each gradient to bounded sensitivity
     * 2. Add Gaussian noise scaled to batch size
     * 3. Update privacy budget
     */
    fun privatizeGradients(
        gradients: List<FloatArray>,
        batchSize: Int,
        clipNorm: Float = sensitivityBound
    ): FloatArray {
        require(gradients.isNotEmpty()) { "Empty gradients list" }
        
        // Clip each gradient
        val clippedGradients = gradients.map { clipGradients(it, clipNorm) }
        
        // Average clipped gradients
        val size = clippedGradients.first().size
        val avgGradients = FloatArray(size)
        
        clippedGradients.forEach { grad ->
            for (i in grad.indices) {
                avgGradients[i] += grad[i]
            }
        }
        
        for (i in avgGradients.indices) {
            avgGradients[i] /= batchSize
        }
        
        // Add Gaussian noise scaled to batch size
        val noiseScale = clipNorm / batchSize
        val sigma = calculateGaussianSigma(noiseScale)
        
        val noisyGradients = FloatArray(size) { i ->
            avgGradients[i] + sampleGaussian(0.0, sigma).toFloat()
        }
        
        // Update privacy budget
        updatePrivacyBudget(sigma, batchSize)
        
        return noisyGradients
    }
    
    /**
     * Calculate Gaussian noise standard deviation
     */
    private fun calculateGaussianSigma(sensitivity: Float): Double {
        // Use strong composition theorem
        return sensitivity * sqrt(2.0 * ln(1.25 / delta)) / epsilon
    }
    
    /**
     * Sample from Laplace distribution
     */
    private fun sampleLaplace(mean: Double, scale: Double): Double {
        val u = random.nextDouble() - 0.5
        return mean - scale * sign(u) * ln(1 - 2 * abs(u))
    }
    
    /**
     * Sample from Gaussian distribution (Box-Muller transform)
     */
    private fun sampleGaussian(mean: Double, stddev: Double): Double {
        val u1 = random.nextDouble()
        val u2 = random.nextDouble()
        val z = sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
        return mean + stddev * z
    }
    
    /**
     * Calculate L2 norm
     */
    private fun l2Norm(values: FloatArray): Float {
        var sum = 0.0f
        values.forEach { sum += it * it }
        return sqrt(sum)
    }
    
    /**
     * Sign function
     */
    private fun sign(x: Double): Double = if (x >= 0) 1.0 else -1.0
    
    /**
     * Update privacy budget using Moment Accountant
     * 
     * Tracks Rényi Differential Privacy for tighter bounds
     */
    private fun updatePrivacyBudget(sigma: Double, batchSize: Int) {
        val q = batchSize.toDouble() / 1000.0  // Sampling ratio (assume dataset size = 1000)
        
        momentOrders.forEach { lambda ->
            // Compute moment for this step
            val moment = computeMoment(lambda, sigma, q)
            moments[lambda] = (moments[lambda] ?: 0.0) + moment
        }
        
        // Convert to (ε, δ)-DP using optimal lambda
        val (eps, _) = momentAccountantToDP()
        consumedEpsilon = eps
        
        Timber.d("Privacy budget consumed: ε=${"%.3f".format(consumedEpsilon)}/$epsilon")
        
        if (consumedEpsilon > epsilon) {
            Timber.w("Privacy budget exhausted!")
        }
    }
    
    /**
     * Compute Rényi divergence moment
     */
    private fun computeMoment(lambda: Double, sigma: Double, q: Double): Double {
        // Simplified moment computation for Gaussian mechanism
        // Full implementation requires numerical integration
        return lambda * q * q / (2.0 * sigma * sigma)
    }
    
    /**
     * Convert moment accountant to (ε, δ)-DP
     */
    private fun momentAccountantToDP(): Pair<Double, Double> {
        var minEpsilon = Double.MAX_VALUE
        
        momentOrders.forEach { lambda ->
            val moment = moments[lambda] ?: 0.0
            val eps = moment + ln(1.0 / delta) / (lambda - 1)
            minEpsilon = minOf(minEpsilon, eps)
        }
        
        return Pair(minEpsilon, delta)
    }
    
    /**
     * Get remaining privacy budget
     */
    fun getRemainingBudget(): Double {
        return maxOf(0.0, epsilon - consumedEpsilon)
    }
    
    /**
     * Check if privacy budget is exhausted
     */
    fun isBudgetExhausted(): Boolean {
        return consumedEpsilon >= epsilon
    }
    
    /**
     * Reset privacy budget
     */
    fun resetBudget() {
        consumedEpsilon = 0.0
        consumedDelta = 0.0
        moments.keys.forEach { moments[it] = 0.0 }
        Timber.i("Privacy budget reset")
    }
    
    /**
     * Get privacy budget statistics
     */
    fun getStats(): PrivacyStats {
        return PrivacyStats(
            epsilon = epsilon,
            delta = delta,
            consumedEpsilon = consumedEpsilon,
            remainingBudget = getRemainingBudget(),
            budgetExhausted = isBudgetExhausted()
        )
    }
    
    data class PrivacyStats(
        val epsilon: Double,
        val delta: Double,
        val consumedEpsilon: Double,
        val remainingBudget: Double,
        val budgetExhausted: Boolean
    )
}
