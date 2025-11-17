package com.cortexn.app.agents

import android.content.Context
import com.cortexn.agents.predictor.NextAppPredictor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Predictor Agent - Next App Prediction
 * 
 * Uses contextual signals and temporal patterns
 * to predict next app launch
 */
class PredictorAgent(private val context: Context) {
    
    private lateinit var predictor: NextAppPredictor
    private var isInitialized = false
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.i("Initializing Predictor Agent...")
            
            predictor = NextAppPredictor(context)
            predictor.initialize()
            
            isInitialized = true
            Timber.i("✓ Predictor Agent initialized")
            true
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Predictor Agent")
            false
        }
    }
    
    suspend fun predict(currentApp: String? = null): PredictionResult = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            throw IllegalStateException("Predictor Agent not initialized")
        }
        
        val predictions = predictor.predictNextApps(currentApp)
        
        PredictionResult(
            predictions = predictions.map { prediction ->
                AppPrediction(
                    packageName = prediction.packageName,
                    appName = prediction.appName,
                    confidence = prediction.confidence,
                    reason = prediction.reason
                )
            }
        )
    }
    
    fun recordLaunch(packageName: String) {
        if (isInitialized) {
            predictor.recordAppLaunch(packageName)
        }
    }
    
    fun shutdown() {
        if (isInitialized) {
            predictor.shutdown()
            isInitialized = false
        }
    }
    
    data class PredictionResult(
        val predictions: List<AppPrediction>
    )
    
    data class AppPrediction(
        val packageName: String,
        val appName: String,
        val confidence: Float,
        val reason: String
    )
}
