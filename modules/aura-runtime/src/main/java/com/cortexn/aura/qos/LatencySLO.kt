package com.cortexn.aura.qos

import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/**
 * Service Level Objective (SLO) tracker for latency constraints
 * 
 * Tracks and enforces latency SLOs with:
 * - Percentile calculations (p50, p95, p99)
 * - SLO violation detection and alerting
 * - Adaptive deadline adjustment
 * - Tail latency analysis
 * 
 * Uses sliding window for recent history
 */
class LatencySLO(
    private val windowSize: Int = 100,
    private val p99TargetMs: Float = 100f,
    private val p95TargetMs: Float = 50f
) {
    // Sliding window of latency samples
    private val latencyWindow = ConcurrentLinkedQueue<Float>()
    
    // SLO violations
    private val p99Violations = AtomicInteger(0)
    private val p95Violations = AtomicInteger(0)
    
    // Total samples
    private val totalSamples = AtomicInteger(0)
    
    /**
     * Records a latency measurement
     */
    fun recordLatency(latencyMs: Float) {
        // Add to window
        latencyWindow.offer(latencyMs)
        
        // Maintain window size
        while (latencyWindow.size > windowSize) {
            latencyWindow.poll()
        }
        
        totalSamples.incrementAndGet()
        
        // Check for SLO violations
        checkViolations(latencyMs)
    }
    
    /**
     * Checks if the latency violates SLO targets
     */
    private fun checkViolations(latencyMs: Float) {
        if (latencyMs > p99TargetMs) {
            p99Violations.incrementAndGet()
            Timber.w("P99 SLO violation: ${latencyMs}ms > ${p99TargetMs}ms")
        }
        
        if (latencyMs > p95TargetMs) {
            p95Violations.incrementAndGet()
            Timber.w("P95 SLO violation: ${latencyMs}ms > ${p95TargetMs}ms")
        }
    }
    
    /**
     * Gets P50 (median) latency
     */
    fun getP50Latency(): Float {
        return getPercentile(50f)
    }
    
    /**
     * Gets P95 latency
     */
    fun getP95Latency(): Float {
        return getPercentile(95f)
    }
    
    /**
     * Gets P99 latency
     */
    fun getP99Latency(): Float {
        return getPercentile(99f)
    }
    
    /**
     * Gets P99.9 latency
     */
    fun getP999Latency(): Float {
        return getPercentile(99.9f)
    }
    
    /**
     * Calculates percentile from the latency window
     */
    private fun getPercentile(percentile: Float): Float {
        if (latencyWindow.isEmpty()) {
            return 0f
        }
        
        val sorted = latencyWindow.toList().sorted()
        val index = ceil((percentile / 100f) * sorted.size).toInt() - 1
        val clampedIndex = index.coerceIn(0, sorted.size - 1)
        
        return sorted[clampedIndex]
    }
    
    /**
     * Gets mean latency
     */
    fun getMeanLatency(): Float {
        if (latencyWindow.isEmpty()) {
            return 0f
        }
        
        return latencyWindow.average().toFloat()
    }
    
    /**
     * Gets minimum latency in window
     */
    fun getMinLatency(): Float {
        return latencyWindow.minOrNull() ?: 0f
    }
    
    /**
     * Gets maximum latency in window
     */
    fun getMaxLatency(): Float {
        return latencyWindow.maxOrNull() ?: 0f
    }
    
    /**
     * Checks if SLO is being met
     */
    fun isSLOMet(): Boolean {
        val p99 = getP99Latency()
        val p95 = getP95Latency()
        
        return p99 <= p99TargetMs && p95 <= p95TargetMs
    }
    
    /**
     * Gets SLO violation rate
     */
    fun getViolationRate(): Float {
        val total = totalSamples.get()
        if (total == 0) {
            return 0f
        }
        
        val violations = p99Violations.get()
        return (violations.toFloat() / total) * 100f
    }
    
    /**
     * Gets recommended deadline based on current latency distribution
     * Returns P99 + 20% buffer
     */
    fun getRecommendedDeadline(): Float {
        val p99 = getP99Latency()
        return p99 * 1.2f
    }
    
    /**
     * Resets all statistics
     */
    fun reset() {
        latencyWindow.clear()
        p99Violations.set(0)
        p95Violations.set(0)
        totalSamples.set(0)
        
        Timber.i("Latency SLO tracker reset")
    }
    
    /**
     * Gets detailed statistics
     */
    fun getStats(): LatencyStats {
        return LatencyStats(
            mean = getMeanLatency(),
            median = getP50Latency(),
            p95 = getP95Latency(),
            p99 = getP99Latency(),
            p999 = getP999Latency(),
            min = getMinLatency(),
            max = getMaxLatency(),
            windowSize = latencyWindow.size,
            totalSamples = totalSamples.get(),
            p99Violations = p99Violations.get(),
            p95Violations = p95Violations.get(),
            violationRate = getViolationRate(),
            sloMet = isSLOMet(),
            p99Target = p99TargetMs,
            p95Target = p95TargetMs,
            recommendedDeadline = getRecommendedDeadline()
        )
    }
    
    /**
     * Generates a histogram of latency distribution
     */
    fun getHistogram(bucketCount: Int = 10): Map<String, Int> {
        if (latencyWindow.isEmpty()) {
            return emptyMap()
        }
        
        val min = getMinLatency()
        val max = getMaxLatency()
        val bucketSize = (max - min) / bucketCount
        
        val histogram = mutableMapOf<String, Int>()
        
        for (i in 0 until bucketCount) {
            val bucketStart = min + (i * bucketSize)
            val bucketEnd = bucketStart + bucketSize
            val key = "${bucketStart.toInt()}-${bucketEnd.toInt()}ms"
            
            val count = latencyWindow.count { latency ->
                latency >= bucketStart && latency < bucketEnd
            }
            
            histogram[key] = count
        }
        
        return histogram
    }
    
    data class LatencyStats(
        val mean: Float,
        val median: Float,
        val p95: Float,
        val p99: Float,
        val p999: Float,
        val min: Float,
        val max: Float,
        val windowSize: Int,
        val totalSamples: Int,
        val p99Violations: Int,
        val p95Violations: Int,
        val violationRate: Float,
        val sloMet: Boolean,
        val p99Target: Float,
        val p95Target: Float,
        val recommendedDeadline: Float
    )
}
