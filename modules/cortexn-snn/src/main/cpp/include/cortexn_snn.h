#ifndef CORTEXN_SNN_H
#define CORTEXN_SNN_H

#include <jni.h>
#include <cstdint>
#include <vector>
#include <memory>

// Platform detection
#ifdef __aarch64__
    #define CORTEXN_ARM64
    #ifdef HAVE_NEON
        #include <arm_neon.h>
    #endif
#endif

namespace cortexn {

/**
 * Leaky Integrate-and-Fire (LIF) neuron parameters
 */
struct LIFParams {
    float tau_mem;        // Membrane time constant (ms)
    float tau_syn;        // Synaptic time constant (ms)
    float v_thresh;       // Spike threshold (mV)
    float v_reset;        // Reset potential (mV)
    float v_rest;         // Resting potential (mV)
    float dt;             // Time step (ms)
    
    LIFParams()
        : tau_mem(10.0f)
        , tau_syn(5.0f)
        , v_thresh(1.0f)
        , v_reset(0.0f)
        , v_rest(0.0f)
        , dt(1.0f) {}
};

/**
 * LIF Layer - Fully connected layer with LIF neurons
 */
class LIFLayer {
public:
    LIFLayer(int input_size, int output_size, const LIFParams& params);
    ~LIFLayer();
    
    // Forward pass: input spikes -> output spikes
    void forward(const float* input_spikes, float* output_spikes, int batch_size);
    
    // Reset membrane potentials
    void reset();
    
    // Load weights from memory
    void load_weights(const float* weights, const float* bias);
    
    // Get layer dimensions
    int get_input_size() const { return input_size_; }
    int get_output_size() const { return output_size_; }
    
private:
    int input_size_;
    int output_size_;
    LIFParams params_;
    
    // Learnable parameters
    std::vector<float> weights_;  // [output_size, input_size]
    std::vector<float> bias_;     // [output_size]
    
    // State variables
    std::vector<float> membrane_potential_;  // [output_size]
    std::vector<float> synaptic_current_;    // [output_size]
    
    // Decay factors (precomputed)
    float beta_mem_;
    float beta_syn_;
    
    // Internal methods
    void compute_synaptic_input(const float* input_spikes, float* synaptic_input);
    void update_neurons(const float* synaptic_input, float* output_spikes);
};

/**
 * Spike encoder - converts continuous values to spike trains
 */
class SpikeEncoder {
public:
    enum class EncodingType {
        RATE,      // Rate-based (Poisson)
        LATENCY,   // Latency-based
        TEMPORAL   // Temporal contrast
    };
    
    SpikeEncoder(int input_size, int num_steps, EncodingType type = EncodingType::RATE);
    ~SpikeEncoder();
    
    // Encode continuous input to spike trains
    // input: [batch_size, input_size]
    // output: [num_steps, batch_size, input_size]
    void encode(const float* input, float* spike_trains, int batch_size);
    
private:
    int input_size_;
    int num_steps_;
    EncodingType type_;
    
    void encode_rate(const float* input, float* spike_trains, int batch_size);
    void encode_latency(const float* input, float* spike_trains, int batch_size);
};

/**
 * Kernel dispatcher - selects optimal kernel for current hardware
 */
namespace kernels {
    // Dense spike matrix-vector product
    void spike_dense_forward(
        const float* spikes,      // [batch_size, input_size]
        const float* weights,     // [output_size, input_size]
        const float* bias,        // [output_size]
        float* output,            // [batch_size, output_size]
        int batch_size,
        int input_size,
        int output_size
    );
    
    // NEON-optimized version
    void spike_dense_neon(
        const float* spikes,
        const float* weights,
        const float* bias,
        float* output,
        int batch_size,
        int input_size,
        int output_size
    );
    
    #ifdef HAVE_SME2
    // SME2-optimized version
    void spike_dense_sme2(
        const float* spikes,
        const float* weights,
        const float* bias,
        float* output,
        int batch_size,
        int input_size,
        int output_size
    );
    #endif
}

} // namespace cortexn

// JNI function declarations
extern "C" {
    JNIEXPORT jlong JNICALL
    Java_com_cortexn_cortexn_CortexNReflex_nativeCreateLayer(
        JNIEnv* env, jobject obj,
        jint input_size, jint output_size,
        jfloat tau_mem, jfloat v_thresh
    );
    
    JNIEXPORT void JNICALL
    Java_com_cortexn_cortexn_CortexNReflex_nativeForward(
        JNIEnv* env, jobject obj,
        jlong layer_ptr,
        jfloatArray input_spikes,
        jfloatArray output_spikes
    );
    
    JNIEXPORT void JNICALL
    Java_com_cortexn_cortexn_CortexNReflex_nativeReset(
        JNIEnv* env, jobject obj,
        jlong layer_ptr
    );
    
    JNIEXPORT void JNICALL
    Java_com_cortexn_cortexn_CortexNReflex_nativeRelease(
        JNIEnv* env, jobject obj,
        jlong layer_ptr
    );
}

#endif // CORTEXN_SNN_H
