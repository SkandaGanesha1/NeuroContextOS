package com.cortexn.app.privacy

import com.cortexn.privacy.DifferentialPrivacy
import timber.log.Timber

/**
 * Differential Privacy wrapper
 * 
 * Applies DP-SGD for privacy-preserving model updates
 */
class DP(
    epsilon: Double = 1.0,
    delta: Double = 1e-5
) {
    private val dp = DifferentialPrivacy(epsilon, delta)
    
    /**
     * Privatize gradients with clipping and noise
     */
    fun privatizeGradients(
        gradients: List<FloatArray>,
        batchSize: Int,
        clipNorm: Float = 1.0f
    ): FloatArray {
        return dp.privatizeGradients(gradients, batchSize, clipNorm)
    }
    
    /**
     * Check if privacy budget is exhausted
     */
    fun isBudgetExhausted(): Boolean {
        return dp.isBudgetExhausted()
    }
    
    /**
     * Get remaining privacy budget
     */
    fun getRemainingBudget(): Double {
        return dp.getRemainingBudget()
    }
    
    /**
     * Get privacy statistics
     */
    fun getStats(): DifferentialPrivacy.PrivacyStats {
        return dp.getStats()
    }
    
    /**
     * Reset privacy budget
     */
    fun resetBudget() {
        dp.resetBudget()
        Timber.i("Privacy budget reset")
    }
}
