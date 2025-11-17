#ifndef SPIKE_ENCODER_H
#define SPIKE_ENCODER_H

#include <cstdint>
#include <cmath>
#include <random>

namespace cortexn {

/**
 * Rate-based spike encoder using Poisson process
 * Probability of spike = sigmoid(gain * input)
 */
class RateEncoder {
public:
    explicit RateEncoder(float gain = 10.0f, uint32_t seed = 42)
        : gain_(gain), rng_(seed), dist_(0.0f, 1.0f) {}
    
    inline float encode(float input) {
        float rate = 1.0f / (1.0f + std::exp(-gain_ * input));
        return (dist_(rng_) < rate) ? 1.0f : 0.0f;
    }
    
private:
    float gain_;
    std::mt19937 rng_;
    std::uniform_real_distribution<float> dist_;
};

/**
 * Latency-based spike encoder
 * Earlier spike time for larger inputs
 */
class LatencyEncoder {
public:
    explicit LatencyEncoder(int num_steps) : num_steps_(num_steps) {}
    
    inline int encode(float input) {
        // Normalize input to [0, 1]
        float normalized = (input + 1.0f) / 2.0f;
        normalized = std::max(0.0f, std::min(1.0f, normalized));
        
        // Convert to spike time (earlier for larger values)
        int spike_time = static_cast<int>((1.0f - normalized) * num_steps_);
        return std::min(spike_time, num_steps_ - 1);
    }
    
private:
    int num_steps_;
};

/**
 * Fast sigmoid approximation for spike encoding
 */
inline float fast_sigmoid(float x) {
    // Piecewise linear approximation
    if (x < -3.0f) return 0.0f;
    if (x > 3.0f) return 1.0f;
    return 0.5f + 0.25f * x;
}

} // namespace cortexn

#endif // SPIKE_ENCODER_H
