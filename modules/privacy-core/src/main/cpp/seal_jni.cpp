#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#ifdef HAVE_SEAL
#include "seal/seal.h"
using namespace seal;
#endif

#define LOG_TAG "SEAL-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * JNI bridge for Microsoft SEAL (Homomorphic Encryption)
 * 
 * Provides encrypted computation capabilities for privacy-preserving ML:
 * - BFV/CKKS encryption schemes
 * - Homomorphic addition/multiplication
 * - Relinearization and rescaling
 */

#ifdef HAVE_SEAL

// Global context
static SEALContext* g_context = nullptr;
static KeyGenerator* g_keygen = nullptr;
static PublicKey* g_public_key = nullptr;
static SecretKey* g_secret_key = nullptr;
static RelinKeys* g_relin_keys = nullptr;

extern "C" {

/**
 * Initialize SEAL with BFV scheme
 */
JNIEXPORT jboolean JNICALL
Java_com_cortexn_privacy_HomomorphicEncryption_nativeInitialize(
    JNIEnv* env,
    jobject obj,
    jint poly_modulus_degree
) {
    try {
        LOGI("Initializing SEAL with poly_modulus_degree=%d", poly_modulus_degree);
        
        // Set encryption parameters
        EncryptionParameters parms(scheme_type::bfv);
        parms.set_poly_modulus_degree(poly_modulus_degree);
        parms.set_coeff_modulus(CoeffModulus::BFVDefault(poly_modulus_degree));
        parms.set_plain_modulus(PlainModulus::Batching(poly_modulus_degree, 20));
        
        // Create context
        g_context = new SEALContext(parms);
        
        if (!g_context->parameters_set()) {
            LOGE("Invalid encryption parameters");
            return JNI_FALSE;
        }
        
        // Generate keys
        g_keygen = new KeyGenerator(*g_context);
        g_secret_key = new SecretKey(g_keygen->secret_key());
        g_keygen->create_public_key(*g_public_key);
        g_keygen->create_relin_keys(*g_relin_keys);
        
        LOGI("SEAL initialized successfully");
        return JNI_TRUE;
        
    } catch (const std::exception& e) {
        LOGE("SEAL initialization failed: %s", e.what());
        return JNI_FALSE;
    }
}

/**
 * Encrypt plaintext array
 */
JNIEXPORT jbyteArray JNICALL
Java_com_cortexn_privacy_HomomorphicEncryption_nativeEncrypt(
    JNIEnv* env,
    jobject obj,
    jfloatArray plaintext_array
) {
    if (!g_context || !g_public_key) {
        LOGE("SEAL not initialized");
        return nullptr;
    }
    
    try {
        // Get plaintext data
        jsize length = env->GetArrayLength(plaintext_array);
        jfloat* plaintext_data = env->GetFloatArrayElements(plaintext_array, nullptr);
        
        // Create plaintext
        Plaintext plain;
        BatchEncoder encoder(*g_context);
        std::vector<int64_t> values(length);
        for (int i = 0; i < length; ++i) {
            values[i] = static_cast<int64_t>(plaintext_data[i] * 1000); // Scale floats
        }
        encoder.encode(values, plain);
        
        // Encrypt
        Encryptor encryptor(*g_context, *g_public_key);
        Ciphertext cipher;
        encryptor.encrypt(plain, cipher);
        
        // Serialize to bytes
        std::stringstream stream;
        cipher.save(stream);
        std::string serialized = stream.str();
        
        // Create Java byte array
        jbyteArray result = env->NewByteArray(serialized.size());
        env->SetByteArrayRegion(result, 0, serialized.size(),
                               reinterpret_cast<const jbyte*>(serialized.data()));
        
        env->ReleaseFloatArrayElements(plaintext_array, plaintext_data, JNI_ABORT);
        
        return result;
        
    } catch (const std::exception& e) {
        LOGE("Encryption failed: %s", e.what());
        return nullptr;
    }
}

/**
 * Decrypt ciphertext to array
 */
JNIEXPORT jfloatArray JNICALL
Java_com_cortexn_privacy_HomomorphicEncryption_nativeDecrypt(
    JNIEnv* env,
    jobject obj,
    jbyteArray ciphertext_bytes
) {
    if (!g_context || !g_secret_key) {
        LOGE("SEAL not initialized");
        return nullptr;
    }
    
    try {
        // Get ciphertext bytes
        jsize length = env->GetArrayLength(ciphertext_bytes);
        jbyte* bytes = env->GetByteArrayElements(ciphertext_bytes, nullptr);
        
        // Deserialize ciphertext
        std::stringstream stream;
        stream.write(reinterpret_cast<const char*>(bytes), length);
        Ciphertext cipher;
        cipher.load(*g_context, stream);
        
        // Decrypt
        Decryptor decryptor(*g_context, *g_secret_key);
        Plaintext plain;
        decryptor.decrypt(cipher, plain);
        
        // Decode to values
        BatchEncoder encoder(*g_context);
        std::vector<int64_t> values;
        encoder.decode(plain, values);
        
        // Convert to float array
        jfloatArray result = env->NewFloatArray(values.size());
        std::vector<jfloat> float_values(values.size());
        for (size_t i = 0; i < values.size(); ++i) {
            float_values[i] = static_cast<jfloat>(values[i]) / 1000.0f;
        }
        env->SetFloatArrayRegion(result, 0, float_values.size(), float_values.data());
        
        env->ReleaseByteArrayElements(ciphertext_bytes, bytes, JNI_ABORT);
        
        return result;
        
    } catch (const std::exception& e) {
        LOGE("Decryption failed: %s", e.what());
        return nullptr;
    }
}

/**
 * Homomorphic addition of two ciphertexts
 */
JNIEXPORT jbyteArray JNICALL
Java_com_cortexn_privacy_HomomorphicEncryption_nativeAdd(
    JNIEnv* env,
    jobject obj,
    jbyteArray cipher1_bytes,
    jbyteArray cipher2_bytes
) {
    if (!g_context) {
        LOGE("SEAL not initialized");
        return nullptr;
    }
    
    try {
        // Load ciphertexts
        jsize len1 = env->GetArrayLength(cipher1_bytes);
        jsize len2 = env->GetArrayLength(cipher2_bytes);
        jbyte* bytes1 = env->GetByteArrayElements(cipher1_bytes, nullptr);
        jbyte* bytes2 = env->GetByteArrayElements(cipher2_bytes, nullptr);
        
        std::stringstream stream1, stream2;
        stream1.write(reinterpret_cast<const char*>(bytes1), len1);
        stream2.write(reinterpret_cast<const char*>(bytes2), len2);
        
        Ciphertext cipher1, cipher2;
        cipher1.load(*g_context, stream1);
        cipher2.load(*g_context, stream2);
        
        // Perform homomorphic addition
        Evaluator evaluator(*g_context);
        Ciphertext result;
        evaluator.add(cipher1, cipher2, result);
        
        // Serialize result
        std::stringstream result_stream;
        result.save(result_stream);
        std::string serialized = result_stream.str();
        
        jbyteArray result_bytes = env->NewByteArray(serialized.size());
        env->SetByteArrayRegion(result_bytes, 0, serialized.size(),
                               reinterpret_cast<const jbyte*>(serialized.data()));
        
        env->ReleaseByteArrayElements(cipher1_bytes, bytes1, JNI_ABORT);
        env->ReleaseByteArrayElements(cipher2_bytes, bytes2, JNI_ABORT);
        
        return result_bytes;
        
    } catch (const std::exception& e) {
        LOGE("Homomorphic addition failed: %s", e.what());
        return nullptr;
    }
}

/**
 * Cleanup SEAL resources
 */
JNIEXPORT void JNICALL
Java_com_cortexn_privacy_HomomorphicEncryption_nativeCleanup(
    JNIEnv* env,
    jobject obj
) {
    delete g_context;
    delete g_keygen;
    delete g_public_key;
    delete g_secret_key;
    delete g_relin_keys;
    
    g_context = nullptr;
    g_keygen = nullptr;
    g_public_key = nullptr;
    g_secret_key = nullptr;
    g_relin_keys = nullptr;
    
    LOGI("SEAL resources cleaned up");
}

} // extern "C"

#else

// Stub implementations when SEAL is not available
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_cortexn_privacy_HomomorphicEncryption_nativeInitialize(
    JNIEnv* env, jobject obj, jint poly_modulus_degree
) {
    LOGE("SEAL not available, homomorphic encryption disabled");
    return JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_cortexn_privacy_HomomorphicEncryption_nativeEncrypt(
    JNIEnv* env, jobject obj, jfloatArray plaintext_array
) {
    return nullptr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_cortexn_privacy_HomomorphicEncryption_nativeDecrypt(
    JNIEnv* env, jobject obj, jbyteArray ciphertext_bytes
) {
    return nullptr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_cortexn_privacy_HomomorphicEncryption_nativeAdd(
    JNIEnv* env, jobject obj, jbyteArray cipher1_bytes, jbyteArray cipher2_bytes
) {
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_cortexn_privacy_HomomorphicEncryption_nativeCleanup(
    JNIEnv* env, jobject obj
) {
    // No-op
}

} // extern "C"

#endif // HAVE_SEAL
