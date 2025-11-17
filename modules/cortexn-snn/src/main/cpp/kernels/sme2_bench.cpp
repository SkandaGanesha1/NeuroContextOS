#include "cortexn_snn.h"
#include <android/log.h>
#include <chrono>
#include <vector>
#include <random>

#define LOG_TAG "CortexN-Bench"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace cortexn {
namespace kernels {

/**
 * Microbenchmark for spike dense kernels
 * Compares performance of different implementations
 */
class SpikeDenseBenchmark {
public:
    struct BenchResult {
        const char* kernel_name;
        double avg_time_ms;
        double throughput_gflops;
        int iterations;
    };
    
    static BenchResult benchmark_kernel(
        void (*kernel_fn)(const float*, const float*, const float*, float*, int, int, int),
        const char* kernel_name,
        int batch_size,
        int input_size,
        int output_size,
        int iterations = 100
    ) {
        // Allocate and initialize data
        std::vector<float> spikes(batch_size * input_size);
        std::vector<float> weights(output_size * input_size);
        std::vector<float> bias(output_size);
        std::vector<float> output(batch_size * output_size);
        
        // Random initialization
        std::mt19937 rng(42);
        std::uniform_real_distribution<float> dist(-1.0f, 1.0f);
        
        for (auto& v : spikes) v = dist(rng);
        for (auto& v : weights) v = dist(rng);
        for (auto& v : bias) v = dist(rng);
        
        // Warmup
        for (int i = 0; i < 10; ++i) {
            kernel_fn(spikes.data(), weights.data(), bias.data(), output.data(),
                     batch_size, input_size, output_size);
        }
        
        // Benchmark
        auto start = std::chrono::high_resolution_clock::now();
        
        for (int i = 0; i < iterations; ++i) {
            kernel_fn(spikes.data(), weights.data(), bias.data(), output.data(),
                     batch_size, input_size, output_size);
        }
        
        auto end = std::chrono::high_resolution_clock::now();
        double elapsed_ms = std::chrono::duration<double, std::milli>(end - start).count();
        double avg_time_ms = elapsed_ms / iterations;
        
        // Calculate GFLOPS: 2 * batch_size * input_size * output_size operations
        double operations = 2.0 * batch_size * input_size * output_size;
        double throughput_gflops = (operations / 1e9) / (avg_time_ms / 1000.0);
        
        LOGI("Benchmark %s: %.3f ms, %.2f GFLOPS", 
             kernel_name, avg_time_ms, throughput_gflops);
        
        return {kernel_name, avg_time_ms, throughput_gflops, iterations};
    }
    
    static void run_all_benchmarks(int batch_size, int input_size, int output_size) {
        LOGI("=== Spike Dense Kernel Benchmark ===");
        LOGI("Configuration: batch=%d, input=%d, output=%d", 
             batch_size, input_size, output_size);
        
        std::vector<BenchResult> results;
        
        // Benchmark scalar implementation
        results.push_back(benchmark_kernel(
            spike_dense_forward, "Scalar", 
            batch_size, input_size, output_size
        ));
        
        #ifdef HAVE_NEON
        results.push_back(benchmark_kernel(
            spike_dense_neon, "NEON", 
            batch_size, input_size, output_size
        ));
        #endif
        
        #ifdef HAVE_SME2
        results.push_back(benchmark_kernel(
            spike_dense_sme2, "SME2", 
            batch_size, input_size, output_size
        ));
        #endif
        
        // Print summary
        LOGI("=== Benchmark Summary ===");
        double baseline_time = results[0].avg_time_ms;
        
        for (const auto& result : results) {
            double speedup = baseline_time / result.avg_time_ms;
            LOGI("%s: %.3f ms (%.2fx speedup, %.2f GFLOPS)", 
                 result.kernel_name, result.avg_time_ms, speedup, result.throughput_gflops);
        }
    }
};

} // namespace kernels
} // namespace cortexn

// JNI export for benchmark
extern "C" {

JNIEXPORT void JNICALL
Java_com_cortexn_cortexn_CortexNReflex_nativeBenchmark(
    JNIEnv* env, jobject obj,
    jint batch_size, jint input_size, jint output_size
) {
    cortexn::kernels::SpikeDenseBenchmark::run_all_benchmarks(
        batch_size, input_size, output_size
    );
}

} // extern "C"
