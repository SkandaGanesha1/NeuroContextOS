package com.cortexn.app

/**
 * Build configuration keys and feature flags
 */
object BuildConfigKeys {
    
    // API Keys (use BuildConfig in production)
    const val OPENAI_API_KEY = ""  // Empty in open source
    
    // Feature Flags
    val ENABLE_TELEMETRY: Boolean = BuildConfig.ENABLE_TELEMETRY
    val ENABLE_FL: Boolean = BuildConfig.ENABLE_FL
    const val ENABLE_SME2 = false  // Enable for ARMv9-A devices
    
    // Model Paths
    const val MODEL_YOLO = "models/yolov8n.onnx"
    const val MODEL_WHISPER = "models/whisper-tiny.pte"
    const val MODEL_LLAMA = "models/llama-3.2-1b-q4.pte"
    const val MODEL_AUDIOGEN_COND = "models/conditioners.tflite"
    const val MODEL_AUDIOGEN_DIT = "models/dit_int8_dynamic.tflite"
    const val MODEL_AUDIOGEN_VAE = "models/autoencoder_fp16.tflite"
    
    // Performance Tuning
    const val DEFAULT_NUM_THREADS = 4
    const val MAX_BATCH_SIZE = 1
    const val ENABLE_FP16 = true
    const val ENABLE_QUANTIZATION = true
    
    // Privacy Settings
    const val DP_EPSILON = 1.0
    const val DP_DELTA = 1e-5
    const val FL_THRESHOLD = 3
    
    // Audio Settings
    const val AUDIO_SAMPLE_RATE = 16000
    const val AUDIO_BUFFER_SIZE = 4096
    
    // Vision Settings
    const val YOLO_INPUT_SIZE = 640
    const val YOLO_CONFIDENCE_THRESHOLD = 0.25f
    const val YOLO_IOU_THRESHOLD = 0.45f
}
