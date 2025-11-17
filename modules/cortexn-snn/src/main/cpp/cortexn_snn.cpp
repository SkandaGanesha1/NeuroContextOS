#include "cortexn_snn.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "CortexN-SNN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace cortexn {

// LIFLayer implementation
LIFLayer::LIFLayer(int input_size, int output_size, const LIFParams& params)
    : input_size_(input_size)
    , output_size_(output_size)
    , params_(params)
    , weights_(input_size * output_size, 0.0f)
    , bias_(output_size, 0.0f)
    , membrane_potential_(output_size, params.v_rest)
    , synaptic_current_(output_size, 0.0f)
{
    // Precompute decay factors
    beta_mem_ = std::exp(-params_.dt / params_.tau_mem);
    beta_syn_ = std::exp(-params_.dt / params_.tau_syn);
    
    LOGI("LIF Layer created: %dx%d, tau_mem=%.2f, thresh=%.2f",
         input_size_, output_size_, params_.tau_mem, params_.v_thresh);
}

LIFLayer::~LIFLayer() {
    LOGI("LIF Layer destroyed");
}

void LIFLayer::load_weights(const float* weights, const float* bias) {
    std::memcpy(weights_.data(), weights, weights_.size() * sizeof(float));
    std::memcpy(bias_.data(), bias, bias_.size() * sizeof(float));
    LOGI("Weights loaded");
}

void LIFLayer::forward(const float* input_spikes, float* output_spikes, int batch_size) {
    // For simplicity, process batch_size=1
    // In production, vectorize across batch
    
    // Compute synaptic input: W * input_spikes + b
    std::vector<float> synaptic_input(output_size_, 0.0f);
    compute_synaptic_input(input_spikes, synaptic_input.data());
    
    // Update neuron dynamics
    update_neurons(synaptic_input.data(), output_spikes);
}

void LIFLayer::compute_synaptic_input(const float* input_spikes, float* synaptic_input) {
    // Use optimized kernel
    kernels::spike_dense_forward(
        input_spikes,
        weights_.data(),
        bias_.data(),
        synaptic_input,
        1, // batch_size
        input_size_,
        output_size_
    );
}

void LIFLayer::update_neurons(const float* synaptic_input, float* output_spikes) {
    for (int i = 0; i < output_size_; ++i) {
        // Update synaptic current with decay
        synaptic_current_[i] = beta_syn_ * synaptic_current_[i] + synaptic_input[i];
        
        // Update membrane potential with decay
        membrane_potential_[i] = beta_mem_ * membrane_potential_[i] + synaptic_current_[i];
        
        // Check for spike
        if (membrane_potential_[i] >= params_.v_thresh) {
            output_spikes[i] = 1.0f;
            membrane_potential_[i] = params_.v_reset;  // Reset
        } else {
            output_spikes[i] = 0.0f;
        }
    }
}

void LIFLayer::reset() {
    std::fill(membrane_potential_.begin(), membrane_potential_.end(), params_.v_rest);
    std::fill(synaptic_current_.begin(), synaptic_current_.end(), 0.0f);
}

// SpikeEncoder implementation
SpikeEncoder::SpikeEncoder(int input_size, int num_steps, EncodingType type)
    : input_size_(input_size)
    , num_steps_(num_steps)
    , type_(type)
{
    LOGI("SpikeEncoder created: size=%d, steps=%d, type=%d",
         input_size_, num_steps_, static_cast<int>(type_));
}

SpikeEncoder::~SpikeEncoder() = default;

void SpikeEncoder::encode(const float* input, float* spike_trains, int batch_size) {
    switch (type_) {
        case EncodingType::RATE:
            encode_rate(input, spike_trains, batch_size);
            break;
        case EncodingType::LATENCY:
            encode_latency(input, spike_trains, batch_size);
            break;
        default:
            LOGE("Unsupported encoding type");
    }
}

void SpikeEncoder::encode_rate(const float* input, float* spike_trains, int batch_size) {
    RateEncoder encoder;
    
    for (int t = 0; t < num_steps_; ++t) {
        for (int b = 0; b < batch_size; ++b) {
            for (int i = 0; i < input_size_; ++i) {
                int idx = t * batch_size * input_size_ + b * input_size_ + i;
                spike_trains[idx] = encoder.encode(input[b * input_size_ + i]);
            }
        }
    }
}

void SpikeEncoder::encode_latency(const float* input, float* spike_trains, int batch_size) {
    LatencyEncoder encoder(num_steps_);
    
    // Initialize spike trains to zero
    std::memset(spike_trains, 0, num_steps_ * batch_size * input_size_ * sizeof(float));
    
    for (int b = 0; b < batch_size; ++b) {
        for (int i = 0; i < input_size_; ++i) {
            int spike_time = encoder.encode(input[b * input_size_ + i]);
            int idx = spike_time * batch_size * input_size_ + b * input_size_ + i;
            spike_trains[idx] = 1.0f;
        }
    }
}

} // namespace cortexn

// JNI implementations
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_cortexn_cortexn_CortexNReflex_nativeCreateLayer(
    JNIEnv* env, jobject obj,
    jint input_size, jint output_size,
    jfloat tau_mem, jfloat v_thresh
) {
    cortexn::LIFParams params;
    params.tau_mem = tau_mem;
    params.v_thresh = v_thresh;
    
    auto* layer = new cortexn::LIFLayer(input_size, output_size, params);
    return reinterpret_cast<jlong>(layer);
}

JNIEXPORT void JNICALL
Java_com_cortexn_cortexn_CortexNReflex_nativeForward(
    JNIEnv* env, jobject obj,
    jlong layer_ptr,
    jfloatArray input_spikes,
    jfloatArray output_spikes
) {
    auto* layer = reinterpret_cast<cortexn::LIFLayer*>(layer_ptr);
    
    jfloat* input_data = env->GetFloatArrayElements(input_spikes, nullptr);
    jfloat* output_data = env->GetFloatArrayElements(output_spikes, nullptr);
    
    layer->forward(input_data, output_data, 1);
    
    env->ReleaseFloatArrayElements(input_spikes, input_data, JNI_ABORT);
    env->ReleaseFloatArrayElements(output_spikes, output_data, 0);
}

JNIEXPORT void JNICALL
Java_com_cortexn_cortexn_CortexNReflex_nativeReset(
    JNIEnv* env, jobject obj,
    jlong layer_ptr
) {
    auto* layer = reinterpret_cast<cortexn::LIFLayer*>(layer_ptr);
    layer->reset();
}

JNIEXPORT void JNICALL
Java_com_cortexn_cortexn_CortexNReflex_nativeRelease(
    JNIEnv* env, jobject obj,
    jlong layer_ptr
) {
    auto* layer = reinterpret_cast<cortexn::LIFLayer*>(layer_ptr);
    delete layer;
}

} // extern "C"
