#include "spike_encoder.h"
#include <android/log.h>

#define LOG_TAG "CortexN-Encoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace cortexn {

// RateEncoder and LatencyEncoder are header-only implementations
// This file can contain additional encoder utilities if needed

/**
 * Temporal contrast encoder for vision applications
 * Generates spikes based on temporal changes in input
 */
class TemporalContrastEncoder {
public:
    TemporalContrastEncoder(int input_size, float threshold = 0.1f)
        : input_size_(input_size)
        , threshold_(threshold)
        , prev_input_(input_size, 0.0f)
    {}
    
    void encode(const float* input, float* on_spikes, float* off_spikes) {
        for (int i = 0; i < input_size_; ++i) {
            float delta = input[i] - prev_input_[i];
            
            if (delta > threshold_) {
                on_spikes[i] = 1.0f;
                off_spikes[i] = 0.0f;
            } else if (delta < -threshold_) {
                on_spikes[i] = 0.0f;
                off_spikes[i] = 1.0f;
            } else {
                on_spikes[i] = 0.0f;
                off_spikes[i] = 0.0f;
            }
            
            prev_input_[i] = input[i];
        }
    }
    
private:
    int input_size_;
    float threshold_;
    std::vector<float> prev_input_;
};

} // namespace cortexn
