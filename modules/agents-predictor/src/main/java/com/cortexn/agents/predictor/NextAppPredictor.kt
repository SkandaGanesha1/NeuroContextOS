package com.cortexn.agents.predictor

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Next-App Predictor using contextual signals and temporal patterns
 * 
 * Prediction signals:
 * - Temporal: Time of day, day of week, seasonal patterns
 * - Sequential: App usage sequences (Markov chains)
 * - Contextual: Location, battery level, network state
 * - Usage frequency: App launch counts, session durations
 * - Recency: Time since last use (exponential decay)
 * 
 * Algorithm:
 * - Combines multiple signals using weighted ensemble
 * - Markov chain for sequence prediction
 * - Exponential time decay for recency
 * - Context-aware scoring
 */
class NextAppPredictor(private val context: Context) {
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // App usage database
    private val appUsageHistory = ConcurrentHashMap<String, AppUsageStats>()
    private val transitionMatrix = ConcurrentHashMap<Pair<String, String>, Int>()
    private var lastUsedApp: String? = null
    
    // Context state
    private val _predictions = MutableStateFlow<List<AppPrediction>>(emptyList())
    val predictions: StateFlow<List<AppPrediction>> = _predictions.asStateFlow()
    
    // Configuration
    private val config = PredictorConfig()
    
    data class PredictorConfig(
        val maxPredictions: Int = 5,
        val minLaunchCountThreshold: Int = 3,
        val timeDecayHalfLife: Long = 24 * 60 * 60 * 1000L, // 24 hours
        val contextWeight: Float = 0.3f,
        val sequenceWeight: Float = 0.4f,
        val temporalWeight: Float = 0.2f,
        val recencyWeight: Float = 0.1f
    )
    
    data class AppUsageStats(
        val packageName: String,
        val appName: String,
        val launchCount: Int = 0,
        val totalTimeMs: Long = 0,
        val lastLaunchTime: Long = 0,
        val hourlyDistribution: IntArray = IntArray(24),  // Launches per hour
        val weekdayDistribution: IntArray = IntArray(7),  // Launches per day of week
        val locationClusters: MutableList<LocationContext> = mutableListOf(),
        val batteryLevelDistribution: IntArray = IntArray(10) // 0-100% in 10% buckets
    ) {
        fun avgSessionDuration(): Long {
            return if (launchCount > 0) totalTimeMs / launchCount else 0
        }
    }
    
    data class AppPrediction(
        val packageName: String,
        val appName: String,
        val confidence: Float,
        val reason: String,
        val iconResId: Int = 0
    )
    
    data class LocationContext(
        val latitude: Double,
        val longitude: Double,
        val radius: Float,
        val launchCount: Int
    )
    
    data class CurrentContext(
        val hour: Int,
        val dayOfWeek: Int,
        val location: Location? = null,
        val batteryLevel: Int,
        val isCharging: Boolean,
        val networkType: String
    )
    
    /**
     * Initialize predictor and load historical data
     */
    fun initialize() {
        Timber.i("Initializing NextAppPredictor")
        
        scope.launch {
            try {
                // Load app usage statistics
                loadAppUsageHistory()
                
                // Build transition matrix
                buildTransitionMatrix()
                
                Timber.i("✓ NextAppPredictor initialized: ${appUsageHistory.size} apps tracked")
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize predictor")
            }
        }
    }
    
    /**
     * Get next-app predictions based on current context
     */
    suspend fun predictNextApps(currentApp: String? = null): List<AppPrediction> = withContext(Dispatchers.Default) {
        val startTime = System.nanoTime()
        
        // Get current context
        val context = getCurrentContext()
        
        // Calculate scores for all apps
        val scores = mutableMapOf<String, Float>()
        
        appUsageHistory.values.forEach { stats ->
            if (stats.launchCount >= config.minLaunchCountThreshold) {
                val score = calculateAppScore(stats, currentApp, context)
                scores[stats.packageName] = score
            }
        }
        
        // Sort by score and take top N
        val predictions = scores.entries
            .sortedByDescending { it.value }
            .take(config.maxPredictions)
            .map { (packageName, score) ->
                val stats = appUsageHistory[packageName]!!
                AppPrediction(
                    packageName = packageName,
                    appName = stats.appName,
                    confidence = score,
                    reason = generateReason(stats, currentApp, context)
                )
            }
        
        val duration = (System.nanoTime() - startTime) / 1_000_000f
        Timber.d("Predictions generated in ${duration}ms: ${predictions.size} apps")
        
        _predictions.value = predictions
        predictions
    }
    
    /**
     * Calculate score for an app based on multiple signals
     */
    private fun calculateAppScore(
        stats: AppUsageStats,
        currentApp: String?,
        context: CurrentContext
    ): Float {
        // 1. Temporal score (time of day, day of week)
        val temporalScore = calculateTemporalScore(stats, context)
        
        // 2. Sequential score (Markov chain)
        val sequenceScore = if (currentApp != null) {
            calculateSequenceScore(currentApp, stats.packageName)
        } else {
            0.0f
        }
        
        // 3. Context score (location, battery, network)
        val contextScore = calculateContextScore(stats, context)
        
        // 4. Recency score (exponential decay)
        val recencyScore = calculateRecencyScore(stats)
        
        // Weighted ensemble
        val finalScore = 
            config.temporalWeight * temporalScore +
            config.sequenceWeight * sequenceScore +
            config.contextWeight * contextScore +
            config.recencyWeight * recencyScore
        
        return finalScore
    }
    
    /**
     * Calculate temporal score based on time patterns
     */
    private fun calculateTemporalScore(stats: AppUsageStats, context: CurrentContext): Float {
        // Hour of day distribution
        val hourlyLaunches = stats.hourlyDistribution[context.hour]
        val maxHourlyLaunches = stats.hourlyDistribution.maxOrNull() ?: 1
        val hourScore = hourlyLaunches.toFloat() / maxHourlyLaunches
        
        // Day of week distribution
        val weekdayLaunches = stats.weekdayDistribution[context.dayOfWeek]
        val maxWeekdayLaunches = stats.weekdayDistribution.maxOrNull() ?: 1
        val weekdayScore = weekdayLaunches.toFloat() / maxWeekdayLaunches
        
        // Combine with weights
        return 0.7f * hourScore + 0.3f * weekdayScore
    }
    
    /**
     * Calculate sequence score using Markov chain
     */
    private fun calculateSequenceScore(fromApp: String, toApp: String): Float {
        val transitionCount = transitionMatrix[Pair(fromApp, toApp)] ?: 0
        
        // Get total transitions from current app
        val totalFromApp = transitionMatrix.entries
            .filter { it.key.first == fromApp }
            .sumOf { it.value }
        
        return if (totalFromApp > 0) {
            transitionCount.toFloat() / totalFromApp
        } else {
            0.0f
        }
    }
    
    /**
     * Calculate context score (location, battery, network)
     */
    private fun calculateContextScore(stats: AppUsageStats, context: CurrentContext): Float {
        var score = 0.0f
        var weights = 0.0f
        
        // Location similarity
        context.location?.let { currentLocation ->
            val locationScore = stats.locationClusters.maxOfOrNull { cluster ->
                val distance = calculateDistance(
                    currentLocation.latitude, currentLocation.longitude,
                    cluster.latitude, cluster.longitude
                )
                
                if (distance < cluster.radius) {
                    1.0f - (distance / cluster.radius)
                } else {
                    0.0f
                }
            } ?: 0.0f
            
            score += 0.5f * locationScore
            weights += 0.5f
        }
        
        // Battery level similarity
        val batteryBucket = context.batteryLevel / 10
        val batteryLaunches = stats.batteryLevelDistribution.getOrNull(batteryBucket) ?: 0
        val maxBatteryLaunches = stats.batteryLevelDistribution.maxOrNull() ?: 1
        val batteryScore = batteryLaunches.toFloat() / maxBatteryLaunches
        
        score += 0.3f * batteryScore
        weights += 0.3f
        
        // Normalize
        return if (weights > 0) score / weights else 0.0f
    }
    
    /**
     * Calculate recency score with exponential decay
     */
    private fun calculateRecencyScore(stats: AppUsageStats): Float {
        if (stats.lastLaunchTime == 0L) return 0.0f
        
        val timeSinceLastUse = System.currentTimeMillis() - stats.lastLaunchTime
        val decay = exp(-timeSinceLastUse.toDouble() / config.timeDecayHalfLife).toFloat()
        
        return decay
    }
    
    /**
     * Load app usage history from system
     */
    private fun loadAppUsageHistory() {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        
        // Query last 30 days
        val endTime = System.currentTimeMillis()
        val startTime = endTime - (30L * 24 * 60 * 60 * 1000)
        
        val usageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )
        
        usageStats?.forEach { stats ->
            if (stats.totalTimeInForeground > 0) {
                updateAppStats(stats)
            }
        }
        
        Timber.d("Loaded usage history for ${appUsageHistory.size} apps")
    }
    
    /**
     * Update app usage statistics
     */
    private fun updateAppStats(usageStats: UsageStats) {
        val packageName = usageStats.packageName
        val appName = getAppName(packageName)
        
        val existing = appUsageHistory[packageName] ?: AppUsageStats(
            packageName = packageName,
            appName = appName
        )
        
        // Update statistics
        val updated = existing.copy(
            launchCount = existing.launchCount + 1,
            totalTimeMs = existing.totalTimeMs + usageStats.totalTimeInForeground,
            lastLaunchTime = usageStats.lastTimeUsed
        )
        
        // Update hourly distribution
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = usageStats.lastTimeUsed
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
        
        updated.hourlyDistribution[hour]++
        updated.weekdayDistribution[dayOfWeek]++
        
        appUsageHistory[packageName] = updated
    }
    
    /**
     * Build app transition matrix (Markov chain)
     */
    private fun buildTransitionMatrix() {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        
        val endTime = System.currentTimeMillis()
        val startTime = endTime - (7L * 24 * 60 * 60 * 1000) // Last 7 days
        
        val events = usageStatsManager.queryEvents(startTime, endTime)
        
        var lastApp: String? = null
        var lastEventTime = 0L
        
        while (events.hasNextEvent()) {
            val event = android.app.usage.UsageEvents.Event()
            events.getNextEvent(event)
            
            if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                val currentApp = event.packageName
                
                // Record transition if within 5 minutes
                if (lastApp != null && (event.timeStamp - lastEventTime) < 5 * 60 * 1000) {
                    val transition = Pair(lastApp, currentApp)
                    transitionMatrix[transition] = (transitionMatrix[transition] ?: 0) + 1
                }
                
                lastApp = currentApp
                lastEventTime = event.timeStamp
            }
        }
        
        Timber.d("Built transition matrix: ${transitionMatrix.size} transitions")
    }
    
    /**
     * Get current context (time, location, battery, etc.)
     */
    private fun getCurrentContext(): CurrentContext {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
        
        // Battery level
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging
        
        // Network type (simplified)
        val networkType = "wifi" // Could use ConnectivityManager for actual detection
        
        return CurrentContext(
            hour = hour,
            dayOfWeek = dayOfWeek,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            networkType = networkType
        )
    }
    
    /**
     * Record app launch event
     */
    fun recordAppLaunch(packageName: String) {
        scope.launch {
            // Update statistics
            val appName = getAppName(packageName)
            val existing = appUsageHistory[packageName] ?: AppUsageStats(
                packageName = packageName,
                appName = appName
            )
            
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
            
            val updated = existing.copy(
                launchCount = existing.launchCount + 1,
                lastLaunchTime = System.currentTimeMillis()
            )
            
            updated.hourlyDistribution[hour]++
            updated.weekdayDistribution[dayOfWeek]++
            
            appUsageHistory[packageName] = updated
            
            // Update transition matrix
            lastUsedApp?.let { prevApp ->
                val transition = Pair(prevApp, packageName)
                transitionMatrix[transition] = (transitionMatrix[transition] ?: 0) + 1
            }
            
            lastUsedApp = packageName
            
            // Refresh predictions
            predictNextApps(packageName)
        }
    }
    
    /**
     * Get app name from package name
     */
    private fun getAppName(packageName: String): String {
        return try {
            val packageManager = context.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
    
    /**
     * Generate human-readable reason for prediction
     */
    private fun generateReason(
        stats: AppUsageStats,
        currentApp: String?,
        context: CurrentContext
    ): String {
        val reasons = mutableListOf<String>()
        
        // Temporal pattern
        val hourlyLaunches = stats.hourlyDistribution[context.hour]
        if (hourlyLaunches > stats.hourlyDistribution.average()) {
            reasons.add("Often used at this time")
        }
        
        // Sequential pattern
        if (currentApp != null) {
            val transitionCount = transitionMatrix[Pair(currentApp, stats.packageName)] ?: 0
            if (transitionCount > 2) {
                reasons.add("Frequently used after ${getAppName(currentApp)}")
            }
        }
        
        // Recency
        val timeSinceLastUse = System.currentTimeMillis() - stats.lastLaunchTime
        if (timeSinceLastUse < 60 * 60 * 1000) { // Within 1 hour
            reasons.add("Used recently")
        }
        
        return reasons.joinToString(", ").ifEmpty { "Based on usage patterns" }
    }
    
    /**
     * Calculate distance between two coordinates (Haversine formula)
     */
    private fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Float {
        val earthRadius = 6371000f // meters
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = kotlin.math.sin(dLat / 2).pow(2) +
                kotlin.math.cos(Math.toRadians(lat1)) *
                kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).pow(2)
        
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        
        return (earthRadius * c).toFloat()
    }
    
    /**
     * Get prediction statistics
     */
    fun getStats(): PredictorStats {
        val totalLaunches = appUsageHistory.values.sumOf { it.launchCount }
        val avgLaunchesPerApp = if (appUsageHistory.isNotEmpty()) {
            totalLaunches.toFloat() / appUsageHistory.size
        } else {
            0f
        }
        
        return PredictorStats(
            trackedApps = appUsageHistory.size,
            totalLaunches = totalLaunches,
            avgLaunchesPerApp = avgLaunchesPerApp,
            transitions = transitionMatrix.size,
            lastPredictionCount = _predictions.value.size
        )
    }
    
    data class PredictorStats(
        val trackedApps: Int,
        val totalLaunches: Int,
        val avgLaunchesPerApp: Float,
        val transitions: Int,
        val lastPredictionCount: Int
    )
    
    /**
     * Shutdown predictor
     */
    fun shutdown() {
        scope.cancel()
        Timber.i("NextAppPredictor shut down")
    }
}
