package com.cortexn.aura.qos

import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Energy budget manager for QoS-aware scheduling
 * 
 * Implements energy-aware task scheduling with:
 * - Per-task energy limits
 * - Global energy budget tracking
 * - Adaptive throttling based on battery state
 * - Energy deficit recovery
 * 
 * Budget allocation strategies:
 * - Token bucket for burst tolerance
 * - Exponential decay for long-term fairness
 * - Battery-level adaptive scaling
 */
class EnergyBudget(
    private val initialBudgetJoules: Float = 10.0f,
    private val maxBudgetJoules: Float = 50.0f,
    private val refillRateJoulesPerSecond: Float = 1.0f
) {
    // Current energy budget (in microjoules for precision)
    private val currentBudgetMicroJoules = AtomicLong((initialBudgetJoules * 1e6).toLong())
    
    // Total energy consumed since start
    private val totalEnergyConsumedMicroJoules = AtomicLong(0L)
    
    // Task count
    private val tasksExecuted = AtomicInteger(0)
    
    // Last refill timestamp
    @Volatile
    private var lastRefillTime = System.nanoTime()
    
    // Battery state
    @Volatile
    private var batteryLevel = 100
    @Volatile
    private var lowPowerMode = false
    
    /**
     * Checks if the budget can afford the requested energy
     */
    fun canAfford(energyJoules: Float): Boolean {
        refillBudget()
        
        val requiredMicroJoules = (energyJoules * 1e6).toLong()
        val currentBudget = currentBudgetMicroJoules.get()
        
        return currentBudget >= requiredMicroJoules
    }
    
    /**
     * Consumes energy from the budget
     * @return true if energy was successfully consumed
     */
    fun consumeEnergy(energyJoules: Float): Boolean {
        val energyMicroJoules = (energyJoules * 1e6).toLong()
        
        // Try to consume energy atomically
        while (true) {
            val currentBudget = currentBudgetMicroJoules.get()
            
            if (currentBudget < energyMicroJoules) {
                Timber.w("Insufficient energy budget: need ${energyJoules}J, have ${currentBudget / 1e6}J")
                return false
            }
            
            val newBudget = currentBudget - energyMicroJoules
            if (currentBudgetMicroJoules.compareAndSet(currentBudget, newBudget)) {
                totalEnergyConsumedMicroJoules.addAndGet(energyMicroJoules)
                tasksExecuted.incrementAndGet()
                
                Timber.d("Energy consumed: ${energyJoules}J, remaining: ${newBudget / 1e6}J")
                return true
            }
        }
    }
    
    /**
     * Refills the budget based on elapsed time and refill rate
     */
    private fun refillBudget() {
        val currentTime = System.nanoTime()
        val elapsedSeconds = (currentTime - lastRefillTime) / 1e9
        
        if (elapsedSeconds < 0.1) {
            return // Refill at most every 100ms
        }
        
        lastRefillTime = currentTime
        
        // Calculate refill amount with battery-aware scaling
        val scaleFactor = getBatteryScaleFactor()
        val refillAmount = (refillRateJoulesPerSecond * elapsedSeconds * scaleFactor * 1e6).toLong()
        
        // Add refill amount, capped at max budget
        while (true) {
            val currentBudget = currentBudgetMicroJoules.get()
            val maxBudgetMicro = (maxBudgetJoules * 1e6).toLong()
            
            val newBudget = min(currentBudget + refillAmount, maxBudgetMicro)
            
            if (currentBudgetMicroJoules.compareAndSet(currentBudget, newBudget)) {
                if (refillAmount > 0) {
                    Timber.v("Budget refilled: +${refillAmount / 1e6}J → ${newBudget / 1e6}J")
                }
                break
            }
        }
    }
    
    /**
     * Updates battery state for adaptive scaling
     */
    fun updateBatteryState(level: Int, lowPowerMode: Boolean) {
        this.batteryLevel = level
        this.lowPowerMode = lowPowerMode
        
        Timber.d("Battery state updated: $level%, low power: $lowPowerMode")
    }
    
    /**
     * Gets scaling factor based on battery level
     * - 100%-80%: 1.0x (full speed)
     * - 80%-50%: 0.8x
     * - 50%-20%: 0.5x
     * - <20%: 0.3x
     * - Low power mode: 0.2x
     */
    private fun getBatteryScaleFactor(): Float {
        if (lowPowerMode) {
            return 0.2f
        }
        
        return when {
            batteryLevel >= 80 -> 1.0f
            batteryLevel >= 50 -> 0.8f
            batteryLevel >= 20 -> 0.5f
            else -> 0.3f
        }
    }
    
    /**
     * Gets current budget in joules
     */
    fun getCurrentBudget(): Float {
        refillBudget()
        return currentBudgetMicroJoules.get() / 1e6f
    }
    
    /**
     * Gets total energy consumed in joules
     */
    fun getTotalEnergyConsumed(): Float {
        return totalEnergyConsumedMicroJoules.get() / 1e6f
    }
    
    /**
     * Gets average energy per task
     */
    fun getAverageEnergyPerTask(): Float {
        val tasks = tasksExecuted.get()
        return if (tasks > 0) {
            getTotalEnergyConsumed() / tasks
        } else {
            0f
        }
    }
    
    /**
     * Resets the budget to initial state
     */
    fun reset() {
        currentBudgetMicroJoules.set((initialBudgetJoules * 1e6).toLong())
        totalEnergyConsumedMicroJoules.set(0L)
        tasksExecuted.set(0)
        lastRefillTime = System.nanoTime()
        
        Timber.i("Energy budget reset")
    }
    
    /**
     * Gets budget statistics
     */
    fun getStats(): BudgetStats {
        refillBudget()
        
        return BudgetStats(
            currentBudgetJoules = getCurrentBudget(),
            maxBudgetJoules = maxBudgetJoules,
            totalConsumedJoules = getTotalEnergyConsumed(),
            averagePerTaskJoules = getAverageEnergyPerTask(),
            tasksExecuted = tasksExecuted.get(),
            utilizationPercent = (getCurrentBudget() / maxBudgetJoules) * 100f,
            batteryLevel = batteryLevel,
            scaleFactor = getBatteryScaleFactor()
        )
    }
    
    data class BudgetStats(
        val currentBudgetJoules: Float,
        val maxBudgetJoules: Float,
        val totalConsumedJoules: Float,
        val averagePerTaskJoules: Float,
        val tasksExecuted: Int,
        val utilizationPercent: Float,
        val batteryLevel: Int,
        val scaleFactor: Float
    )
}
