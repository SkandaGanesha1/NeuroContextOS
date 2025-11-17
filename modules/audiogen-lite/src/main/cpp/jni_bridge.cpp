#include <jni.h>
#include "audiogen.h"
#include <android/log.h>
#include <memory>
#include <string>

#define LOG_TAG "AudioGen-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace audiogen;

// JNI helper to convert jstring to std::string
std::string jstring_to_string(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string str(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return str;
}

extern "C" {

/**
 * Create native AudioGen engine
 */
JNIEXPORT jlong JNICALL
Java_com_cortexn_audiogen_AudioGen_nativeCreate(
    JNIEnv* env,
    jobject obj,
    jstring model_dir,
    jboolean use_gpu,
    jint num_threads
) {
    std::string dir = jstring_to_string(env, model_dir);
    
    LOGI("Creating AudioGen engine: dir=%s, gpu=%d, threads=%d", 
         dir.c_str(), use_gpu, num_threads);
    
    try {
        auto* engine = new AudioGenEngine(dir, use_gpu, num_threads);
        return reinterpret_cast<jlong>(engine);
    } catch (const std::exception& e) {
        LOGE("Failed to create engine: %s", e.what());
        return 0;
    }
}

/**
 * Initialize engine and load models
 */
JNIEXPORT jboolean JNICALL
Java_com_cortexn_audiogen_AudioGen_nativeInitialize(
    JNIEnv* env,
    jobject obj,
    jlong engine_ptr
) {
    auto* engine = reinterpret_cast<AudioGenEngine*>(engine_ptr);
    if (!engine) {
        LOGE("Invalid engine pointer");
        return JNI_FALSE;
    }
    
    return engine->initialize() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Generate audio from text prompt
 */
JNIEXPORT jfloatArray JNICALL
Java_com_cortexn_audiogen_AudioGen_nativeGenerate(
    JNIEnv* env,
    jobject obj,
    jlong engine_ptr,
    jstring prompt,
    jfloat duration,
    jint num_steps,
    jfloat guidance_scale
) {
    auto* engine = reinterpret_cast<AudioGenEngine*>(engine_ptr);
    if (!engine) {
        LOGE("Invalid engine pointer");
        return nullptr;
    }
    
    AudioGenParams params;
    params.prompt = jstring_to_string(env, prompt);
    params.duration_seconds = duration;
    params.num_inference_steps = num_steps;
    params.guidance_scale = guidance_scale;
    
    // Generate audio
    auto result = engine->generate(params);
    
    if (!result.success) {
        LOGE("Generation failed: %s", result.error_message.c_str());
        return nullptr;
    }
    
    // Create Java float array
    jfloatArray audio_array = env->NewFloatArray(result.audio_data.size());
    if (!audio_array) {
        LOGE("Failed to allocate Java array");
        return nullptr;
    }
    
    env->SetFloatArrayRegion(audio_array, 0, result.audio_data.size(), result.audio_data.data());
    
    return audio_array;
}

/**
 * Generate with progress callback
 */
JNIEXPORT jfloatArray JNICALL
Java_com_cortexn_audiogen_AudioGen_nativeGenerateWithProgress(
    JNIEnv* env,
    jobject obj,
    jlong engine_ptr,
    jstring prompt,
    jfloat duration,
    jint num_steps,
    jfloat guidance_scale,
    jobject callback
) {
    auto* engine = reinterpret_cast<AudioGenEngine*>(engine_ptr);
    if (!engine) {
        LOGE("Invalid engine pointer");
        return nullptr;
    }
    
    // Get callback method
    jclass callback_class = env->GetObjectClass(callback);
    jmethodID callback_method = env->GetMethodID(
        callback_class,
        "onProgress",
        "(IILjava/lang/String;)V"
    );
    
    if (!callback_method) {
        LOGE("Failed to find callback method");
        return nullptr;
    }
    
    AudioGenParams params;
    params.prompt = jstring_to_string(env, prompt);
    params.duration_seconds = duration;
    params.num_inference_steps = num_steps;
    params.guidance_scale = guidance_scale;
    
    // Create progress callback wrapper
    ProgressCallback progress_cb = [env, callback, callback_method](
        int current, int total, const std::string& status
    ) {
        jstring status_str = env->NewStringUTF(status.c_str());
        env->CallVoidMethod(callback, callback_method, current, total, status_str);
        env->DeleteLocalRef(status_str);
    };
    
    // Generate audio
    auto result = engine->generate(params, progress_cb);
    
    if (!result.success) {
        LOGE("Generation failed: %s", result.error_message.c_str());
        return nullptr;
    }
    
    // Create Java float array
    jfloatArray audio_array = env->NewFloatArray(result.audio_data.size());
    if (!audio_array) {
        LOGE("Failed to allocate Java array");
        return nullptr;
    }
    
    env->SetFloatArrayRegion(audio_array, 0, result.audio_data.size(), result.audio_data.data());
    
    return audio_array;
}

/**
 * Cancel ongoing generation
 */
JNIEXPORT void JNICALL
Java_com_cortexn_audiogen_AudioGen_nativeCancel(
    JNIEnv* env,
    jobject obj,
    jlong engine_ptr
) {
    auto* engine = reinterpret_cast<AudioGenEngine*>(engine_ptr);
    if (engine) {
        engine->cancel();
    }
}

/**
 * Destroy engine
 */
JNIEXPORT void JNICALL
Java_com_cortexn_audiogen_AudioGen_nativeDestroy(
    JNIEnv* env,
    jobject obj,
    jlong engine_ptr
) {
    auto* engine = reinterpret_cast<AudioGenEngine*>(engine_ptr);
    if (engine) {
        delete engine;
        LOGI("Engine destroyed");
    }
}

/**
 * Create ring buffer
 */
JNIEXPORT jlong JNICALL
Java_com_cortexn_audiogen_AudioGen_nativeCreateRingBuffer(
    JNIEnv* env,
    jobject obj,
    jint capacity
) {
    try {
        auto* buffer = new RingBuffer(capacity);
        return reinterpret_cast<jlong>(buffer);
    } catch (const std::exception& e) {
        LOGE("Failed to create ring buffer: %s", e.what());
        return 0;
    }
}

/**
 * Write to ring buffer
 */
JNIEXPORT jint JNICALL
Java_com_cortexn_audiogen_AudioGen_nativeRingBufferWrite(
    JNIEnv* env,
    jobject obj,
    jlong buffer_ptr,
    jfloatArray data,
    jint count
) {
    auto* buffer = reinterpret_cast<RingBuffer*>(buffer_ptr);
    if (!buffer) return 0;
    
    jfloat* data_ptr = env->GetFloatArrayElements(data, nullptr);
    size_t written = buffer->write(data_ptr, count);
    env->ReleaseFloatArrayElements(data, data_ptr, JNI_ABORT);
    
    return written;
}

/**
 * Read from ring buffer
 */
JNIEXPORT jint JNICALL
Java_com_cortexn_audiogen_AudioGen_nativeRingBufferRead(
    JNIEnv* env,
    jobject obj,
    jlong buffer_ptr,
    jfloatArray data,
    jint count
) {
    auto* buffer = reinterpret_cast<RingBuffer*>(buffer_ptr);
    if (!buffer) return 0;
    
    jfloat* data_ptr = env->GetFloatArrayElements(data, nullptr);
    size_t read = buffer->read(data_ptr, count);
    env->ReleaseFloatArrayElements(data, data_ptr, 0);
    
    return read;
}

/**
 * Get available samples in ring buffer
 */
JNIEXPORT jint JNICALL
Java_com_cortexn_audiogen_AudioGen_nativeRingBufferAvailable(
    JNIEnv* env,
    jobject obj,
    jlong buffer_ptr
) {
    auto* buffer = reinterpret_cast<RingBuffer*>(buffer_ptr);
    return buffer ? buffer->available() : 0;
}

/**
 * Clear ring buffer
 */
JNIEXPORT void JNICALL
Java_com_cortexn_audiogen_AudioGen_nativeRingBufferClear(
    JNIEnv* env,
    jobject obj,
    jlong buffer_ptr
) {
    auto* buffer = reinterpret_cast<RingBuffer*>(buffer_ptr);
    if (buffer) {
        buffer->clear();
    }
}

/**
 * Destroy ring buffer
 */
JNIEXPORT void JNICALL
Java_com_cortexn_audiogen_AudioGen_nativeDestroyRingBuffer(
    JNIEnv* env,
    jobject obj,
    jlong buffer_ptr
) {
    auto* buffer = reinterpret_cast<RingBuffer*>(buffer_ptr);
    if (buffer) {
        delete buffer;
    }
}

} // extern "C"
