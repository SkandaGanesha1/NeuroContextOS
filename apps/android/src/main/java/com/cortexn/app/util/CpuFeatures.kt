package com.cortexn.app.util

import timber.log.Timber

/**
 * CPU Feature Detection
 * 
 * Detects ARM SIMD capabilities:
 * - NEON (ARMv8-A)
 * - I8MM (ARMv8.6-A)
 * - SME2 (ARMv9-A)
 * - Crypto extensions
 * - DotProd
 */
object CpuFeatures {
    
    init {
        System.loadLibrary("cortexn_native")
    }
    
    data class Features(
        val hasNeon: Boolean,
        val hasI8MM: Boolean,
        val hasSME2: Boolean,
        val hasCrypto: Boolean,
        val hasDotProd: Boolean,
        val cpuModel: String
    )
    
    /**
     * Detect CPU features
     */
    fun detect(): Features {
        return Features(
            hasNeon = hasNEON(),
            hasI8MM = hasI8MM(),
            hasSME2 = hasSME2(),
            hasCrypto = hasCrypto(),
            hasDotProd = hasDotProd(),
            cpuModel = getCpuModel()
        )
    }
    
    /**
     * Log detected features
     */
    fun logFeatures() {
        val features = detect()
        Timber.i("=== CPU Features ===")
        Timber.i("  Model: ${features.cpuModel}")
        Timber.i("  NEON: ${features.hasNeon}")
        Timber.i("  I8MM: ${features.hasI8MM}")
        Timber.i("  SME2: ${features.hasSME2}")
        Timber.i("  Crypto: ${features.hasCrypto}")
        Timber.i("  DotProd: ${features.hasDotProd}")
        Timber.i("====================")
    }
    
    // Native methods
    private external fun hasNEON(): Boolean
    private external fun hasI8MM(): Boolean
    private external fun hasSME2(): Boolean
    private external fun hasCrypto(): Boolean
    private external fun hasDotProd(): Boolean
    private external fun getCpuModel(): String
}
