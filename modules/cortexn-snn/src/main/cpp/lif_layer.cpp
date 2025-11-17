#include "cortexn_snn.h"
#include "spike_encoder.h"
#include <android/log.h>
#include <cstring>
#include <algorithm>

#define LOG_TAG "CortexN-LIF"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace cortexn {
namespace kernels {

/**
 * Baseline C++ implementation of spike dense forward
 * Computes: output = weights @ spikes + bias
 */
void spike_dense_forward(
    const float* spikes,
    const float* weights,
    const float* bias,
    float* output,
    int batch_size,
    int input_size,
    int output_size
) {
    // Dispatch to optimized kernel if available
    #ifdef HAVE_SME2
    spike_dense_sme2(spikes, weights, bias, output, batch_size, input_size, output_size);
    return;
    #elif defined(HAVE_NEON)
    spike_dense_neon(spikes, weights, bias, output, batch_size, input_size, output_size);
    return;
    #endif
    
    // Fallback: scalar implementation
    for (int b = 0; b < batch_size; ++b) {
        for (int o = 0; o < output_size; ++o) {
            float sum = bias[o];
            
            for (int i = 0; i < input_size; ++i) {
                sum += weights[o * input_size + i] * spikes[b * input_size + i];
            }
            
            output[b * output_size + o] = sum;
        }
    }
}

} // namespace kernels
} // namespace cortexn
