#include "cortexn_snn.h"
#include <android/log.h>

#ifdef HAVE_NEON
#include <arm_neon.h>
#endif

#define LOG_TAG "CortexN-NEON"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace cortexn {
namespace kernels {

#ifdef HAVE_NEON

/**
 * NEON-optimized spike dense forward kernel
 * Processes 4 floats per iteration using SIMD
 */
void spike_dense_neon(
    const float* spikes,
    const float* weights,
    const float* bias,
    float* output,
    int batch_size,
    int input_size,
    int output_size
) {
    LOGD("Using NEON kernel: batch=%d, input=%d, output=%d", 
         batch_size, input_size, output_size);
    
    for (int b = 0; b < batch_size; ++b) {
        const float* spike_row = spikes + b * input_size;
        float* output_row = output + b * output_size;
        
        for (int o = 0; o < output_size; ++o) {
            const float* weight_row = weights + o * input_size;
            
            // Initialize accumulator with bias
            float32x4_t sum_vec = vdupq_n_f32(0.0f);
            float sum_scalar = bias[o];
            
            // Process 4 elements at a time
            int i = 0;
            for (; i + 3 < input_size; i += 4) {
                float32x4_t spike_vec = vld1q_f32(spike_row + i);
                float32x4_t weight_vec = vld1q_f32(weight_row + i);
                
                // Fused multiply-accumulate
                sum_vec = vmlaq_f32(sum_vec, spike_vec, weight_vec);
            }
            
            // Horizontal sum of vector
            float32x2_t sum_pair = vadd_f32(vget_low_f32(sum_vec), vget_high_f32(sum_vec));
            sum_scalar += vget_lane_f32(vpadd_f32(sum_pair, sum_pair), 0);
            
            // Handle remaining elements
            for (; i < input_size; ++i) {
                sum_scalar += weight_row[i] * spike_row[i];
            }
            
            output_row[o] = sum_scalar;
        }
    }
}

/**
 * NEON-optimized INT8 quantized spike dense forward
 * Uses I8MM instructions if available (ARMv8.6-A+)
 */
void spike_dense_neon_int8(
    const int8_t* spikes,
    const int8_t* weights,
    const int32_t* bias,
    int8_t* output,
    int batch_size,
    int input_size,
    int output_size,
    float scale
) {
    #ifdef HAVE_I8MM
    LOGD("Using NEON I8MM kernel");
    
    for (int b = 0; b < batch_size; ++b) {
        const int8_t* spike_row = spikes + b * input_size;
        int8_t* output_row = output + b * output_size;
        
        for (int o = 0; o < output_size; ++o) {
            const int8_t* weight_row = weights + o * input_size;
            
            int32x4_t sum_vec = vdupq_n_s32(0);
            int32_t sum_scalar = bias[o];
            
            // Process 16 elements at a time using I8MM
            int i = 0;
            for (; i + 15 < input_size; i += 16) {
                int8x16_t spike_vec = vld1q_s8(spike_row + i);
                int8x16_t weight_vec = vld1q_s8(weight_row + i);
                
                // Multiply-accumulate INT8 -> INT32
                // Note: I8MM intrinsics require ARMv8.6-A+
                int16x8_t prod_lo = vmull_s8(vget_low_s8(spike_vec), vget_low_s8(weight_vec));
                int16x8_t prod_hi = vmull_s8(vget_high_s8(spike_vec), vget_high_s8(weight_vec));
                
                sum_vec = vaddq_s32(sum_vec, vpaddlq_s16(prod_lo));
                sum_vec = vaddq_s32(sum_vec, vpaddlq_s16(prod_hi));
            }
            
            // Horizontal sum
            int32x2_t sum_pair = vadd_s32(vget_low_s32(sum_vec), vget_high_s32(sum_vec));
            sum_scalar += vget_lane_s32(vpadd_s32(sum_pair, sum_pair), 0);
            
            // Remaining elements
            for (; i < input_size; ++i) {
                sum_scalar += static_cast<int32_t>(weight_row[i]) * spike_row[i];
            }
            
            // Quantize output
            output_row[o] = static_cast<int8_t>(std::max(-128, std::min(127, 
                static_cast<int>(sum_scalar * scale))));
        }
    }
    #else
    // Fallback to FP32 NEON
    std::vector<float> spikes_fp(batch_size * input_size);
    std::vector<float> weights_fp(output_size * input_size);
    std::vector<float> bias_fp(output_size);
    std::vector<float> output_fp(batch_size * output_size);
    
    // Dequantize
    for (int i = 0; i < batch_size * input_size; ++i) {
        spikes_fp[i] = spikes[i] / 127.0f;
    }
    for (int i = 0; i < output_size * input_size; ++i) {
        weights_fp[i] = weights[i] / 127.0f;
    }
    for (int i = 0; i < output_size; ++i) {
        bias_fp[i] = bias[i] / 127.0f;
    }
    
    spike_dense_neon(spikes_fp.data(), weights_fp.data(), bias_fp.data(), 
                     output_fp.data(), batch_size, input_size, output_size);
    
    // Quantize output
    for (int i = 0; i < batch_size * output_size; ++i) {
        output[i] = static_cast<int8_t>(std::max(-128, std::min(127, 
            static_cast<int>(output_fp[i] * 127.0f))));
    }
    #endif
}

#else

// Fallback if NEON not available
void spike_dense_neon(
    const float* spikes,
    const float* weights,
    const float* bias,
    float* output,
    int batch_size,
    int input_size,
    int output_size
) {
    spike_dense_forward(spikes, weights, bias, output, batch_size, input_size, output_size);
}

#endif // HAVE_NEON

} // namespace kernels
} // namespace cortexn
