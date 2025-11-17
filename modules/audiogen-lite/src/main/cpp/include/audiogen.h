#ifndef AUDIOGEN_H
#define AUDIOGEN_H

#include <memory>
#include <string>
#include <vector>
#include <atomic>
#include <functional>

namespace audiogen {

/**
 * Audio generation parameters
 */
struct AudioGenParams {
    std::string prompt;               // Text prompt for conditioning
    float duration_seconds = 10.0f;   // Target audio duration
    int sample_rate = 16000;          // Output sample rate (16kHz)
    int num_inference_steps = 50;     // Diffusion steps
    float guidance_scale = 3.0f;      // Classifier-free guidance strength
    int seed = -1;                    // Random seed (-1 for random)
    
    // Advanced parameters
    float temperature = 1.0f;         // Sampling temperature
    float top_k = 0.0f;               // Top-k filtering (0 = disabled)
    bool use_karras_sigmas = true;    // Use Karras noise schedule
};

/**
 * Audio generation result
 */
struct AudioGenResult {
    std::vector<float> audio_data;    // Generated audio samples
    int sample_rate;                  // Sample rate of audio
    float generation_time_ms;         // Time taken to generate
    bool success;                     // Whether generation succeeded
    std::string error_message;        // Error message if failed
};

/**
 * Progress callback for generation updates
 */
using ProgressCallback = std::function<void(int current_step, int total_steps, const std::string& status)>;

/**
 * AudioGen engine using TensorFlow Lite
 * 
 * Architecture:
 * 1. Conditioner: Text prompt → conditioning embeddings
 * 2. DiT (Diffusion Transformer): Iterative denoising in latent space
 * 3. AutoEncoder (VAE): Latent → waveform reconstruction
 */
class AudioGenEngine {
public:
    /**
     * Constructor
     * @param model_dir Directory containing TFLite models
     * @param use_gpu Whether to use GPU delegate
     * @param num_threads Number of CPU threads for inference
     */
    AudioGenEngine(
        const std::string& model_dir,
        bool use_gpu = false,
        int num_threads = 4
    );
    
    ~AudioGenEngine();
    
    /**
     * Initialize the engine and load models
     * @return true if successful
     */
    bool initialize();
    
    /**
     * Generate audio from text prompt
     * @param params Generation parameters
     * @param callback Optional progress callback
     * @return Generated audio result
     */
    AudioGenResult generate(
        const AudioGenParams& params,
        ProgressCallback callback = nullptr
    );
    
    /**
     * Check if engine is initialized
     */
    bool is_initialized() const { return initialized_; }
    
    /**
     * Cancel ongoing generation
     */
    void cancel();
    
private:
    // Model paths
    std::string model_dir_;
    std::string conditioner_path_;
    std::string dit_path_;
    std::string autoencoder_path_;
    
    // TFLite interpreters (opaque pointers)
    struct TFLiteInterpreter;
    std::unique_ptr<TFLiteInterpreter> conditioner_;
    std::unique_ptr<TFLiteInterpreter> dit_;
    std::unique_ptr<TFLiteInterpreter> autoencoder_;
    
    // Configuration
    bool use_gpu_;
    int num_threads_;
    std::atomic<bool> initialized_{false};
    std::atomic<bool> cancel_requested_{false};
    
    // Internal methods
    bool load_models();
    std::vector<float> encode_prompt(const std::string& prompt);
    std::vector<float> diffusion_loop(
        const std::vector<float>& conditioning,
        int num_steps,
        float guidance_scale,
        ProgressCallback callback
    );
    std::vector<float> decode_latent(const std::vector<float>& latent);
    
    // Noise scheduling
    std::vector<float> get_karras_sigmas(int num_steps);
    std::vector<float> add_noise(const std::vector<float>& latent, float sigma);
};

/**
 * Ring buffer for real-time audio streaming
 */
class RingBuffer {
public:
    explicit RingBuffer(size_t capacity);
    ~RingBuffer();
    
    bool write(const float* data, size_t count);
    size_t read(float* data, size_t count);
    
    size_t available() const;
    void clear();
    
private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace audiogen

#endif // AUDIOGEN_H
