#include "audiogen.h"
#include "tensorflow/lite/interpreter.h"
#include "tensorflow/lite/kernels/register.h"
#include "tensorflow/lite/model.h"
#include "tensorflow/lite/optional_debug_tools.h"
#include "tensorflow/lite/delegates/gpu/delegate.h"
#include "tensorflow/lite/delegates/xnnpack/xnnpack_delegate.h"

#include <android/log.h>
#include <cmath>
#include <random>
#include <algorithm>
#include <chrono>

#define LOG_TAG "AudioGen"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace audiogen {

// TFLite interpreter wrapper
struct AudioGenEngine::TFLiteInterpreter {
    std::unique_ptr<tflite::FlatBufferModel> model;
    std::unique_ptr<tflite::Interpreter> interpreter;
    TfLiteDelegate* delegate = nullptr;
};

AudioGenEngine::AudioGenEngine(
    const std::string& model_dir,
    bool use_gpu,
    int num_threads
)
    : model_dir_(model_dir)
    , use_gpu_(use_gpu)
    , num_threads_(num_threads)
{
    conditioner_path_ = model_dir_ + "/conditioners.tflite";
    dit_path_ = model_dir_ + "/dit_int8_dynamic.tflite";
    autoencoder_path_ = model_dir_ + "/autoencoder_fp16.tflite";
    
    LOGI("AudioGen engine created: GPU=%d, threads=%d", use_gpu_, num_threads_);
}

AudioGenEngine::~AudioGenEngine() {
    // Cleanup TFLite resources
    if (conditioner_ && conditioner_->delegate) {
        TfLiteXNNPackDelegateDelete(conditioner_->delegate);
    }
    if (dit_ && dit_->delegate) {
        TfLiteXNNPackDelegateDelete(dit_->delegate);
    }
    if (autoencoder_ && autoencoder_->delegate) {
        TfLiteGpuDelegateV2Delete(autoencoder_->delegate);
    }
    
    LOGI("AudioGen engine destroyed");
}

bool AudioGenEngine::initialize() {
    LOGI("Initializing AudioGen engine...");
    
    if (!load_models()) {
        LOGE("Failed to load models");
        return false;
    }
    
    initialized_ = true;
    LOGI("✓ AudioGen engine initialized");
    return true;
}

bool AudioGenEngine::load_models() {
    // Load conditioner model
    LOGI("Loading conditioner: %s", conditioner_path_.c_str());
    conditioner_ = std::make_unique<TFLiteInterpreter>();
    conditioner_->model = tflite::FlatBufferModel::BuildFromFile(conditioner_path_.c_str());
    
    if (!conditioner_->model) {
        LOGE("Failed to load conditioner model");
        return false;
    }
    
    tflite::ops::builtin::BuiltinOpResolver resolver;
    tflite::InterpreterBuilder builder(*conditioner_->model, resolver);
    builder(&conditioner_->interpreter);
    
    if (!conditioner_->interpreter) {
        LOGE("Failed to create conditioner interpreter");
        return false;
    }
    
    // Configure XNNPACK delegate for CPU
    TfLiteXNNPackDelegateOptions xnnpack_options = TfLiteXNNPackDelegateOptionsDefault();
    xnnpack_options.num_threads = num_threads_;
    conditioner_->delegate = TfLiteXNNPackDelegateCreate(&xnnpack_options);
    conditioner_->interpreter->ModifyGraphWithDelegate(conditioner_->delegate);
    
    conditioner_->interpreter->SetNumThreads(num_threads_);
    conditioner_->interpreter->AllocateTensors();
    
    LOGI("✓ Conditioner loaded");
    
    // Load DiT model
    LOGI("Loading DiT: %s", dit_path_.c_str());
    dit_ = std::make_unique<TFLiteInterpreter>();
    dit_->model = tflite::FlatBufferModel::BuildFromFile(dit_path_.c_str());
    
    if (!dit_->model) {
        LOGE("Failed to load DiT model");
        return false;
    }
    
    tflite::InterpreterBuilder dit_builder(*dit_->model, resolver);
    dit_builder(&dit_->interpreter);
    
    if (!dit_->interpreter) {
        LOGE("Failed to create DiT interpreter");
        return false;
    }
    
    // XNNPACK for DiT
    dit_->delegate = TfLiteXNNPackDelegateCreate(&xnnpack_options);
    dit_->interpreter->ModifyGraphWithDelegate(dit_->delegate);
    dit_->interpreter->SetNumThreads(num_threads_);
    dit_->interpreter->AllocateTensors();
    
    LOGI("✓ DiT loaded");
    
    // Load AutoEncoder model
    LOGI("Loading AutoEncoder: %s", autoencoder_path_.c_str());
    autoencoder_ = std::make_unique<TFLiteInterpreter>();
    autoencoder_->model = tflite::FlatBufferModel::BuildFromFile(autoencoder_path_.c_str());
    
    if (!autoencoder_->model) {
        LOGE("Failed to load AutoEncoder model");
        return false;
    }
    
    tflite::InterpreterBuilder ae_builder(*autoencoder_->model, resolver);
    ae_builder(&autoencoder_->interpreter);
    
    if (!autoencoder_->interpreter) {
        LOGE("Failed to create AutoEncoder interpreter");
        return false;
    }
    
    // Use GPU delegate for AutoEncoder if available
    if (use_gpu_) {
        TfLiteGpuDelegateOptionsV2 gpu_options = TfLiteGpuDelegateOptionsV2Default();
        gpu_options.inference_priority1 = TFLITE_GPU_INFERENCE_PRIORITY_MIN_LATENCY;
        autoencoder_->delegate = TfLiteGpuDelegateV2Create(&gpu_options);
        
        if (autoencoder_->interpreter->ModifyGraphWithDelegate(autoencoder_->delegate) != kTfLiteOk) {
            LOGW("GPU delegate failed, falling back to CPU");
            TfLiteGpuDelegateV2Delete(autoencoder_->delegate);
            autoencoder_->delegate = nullptr;
        } else {
            LOGI("✓ GPU delegate enabled for AutoEncoder");
        }
    }
    
    if (!autoencoder_->delegate) {
        // Fallback to XNNPACK
        autoencoder_->delegate = TfLiteXNNPackDelegateCreate(&xnnpack_options);
        autoencoder_->interpreter->ModifyGraphWithDelegate(autoencoder_->delegate);
    }
    
    autoencoder_->interpreter->SetNumThreads(num_threads_);
    autoencoder_->interpreter->AllocateTensors();
    
    LOGI("✓ AutoEncoder loaded");
    
    return true;
}

AudioGenResult AudioGenEngine::generate(
    const AudioGenParams& params,
    ProgressCallback callback
) {
    if (!initialized_) {
        return {
            .audio_data = {},
            .sample_rate = 0,
            .generation_time_ms = 0.0f,
            .success = false,
            .error_message = "Engine not initialized"
        };
    }
    
    cancel_requested_ = false;
    auto start_time = std::chrono::high_resolution_clock::now();
    
    LOGI("Generating audio: prompt='%s', duration=%.1fs, steps=%d",
         params.prompt.c_str(), params.duration_seconds, params.num_inference_steps);
    
    try {
        // Step 1: Encode prompt to conditioning
        if (callback) callback(0, params.num_inference_steps, "Encoding prompt...");
        auto conditioning = encode_prompt(params.prompt);
        
        if (cancel_requested_) {
            return {.success = false, .error_message = "Cancelled"};
        }
        
        // Step 2: Run diffusion loop
        if (callback) callback(0, params.num_inference_steps, "Generating latent...");
        auto latent = diffusion_loop(
            conditioning,
            params.num_inference_steps,
            params.guidance_scale,
            callback
        );
        
        if (cancel_requested_) {
            return {.success = false, .error_message = "Cancelled"};
        }
        
        // Step 3: Decode latent to waveform
        if (callback) callback(params.num_inference_steps, params.num_inference_steps, "Decoding audio...");
        auto audio = decode_latent(latent);
        
        auto end_time = std::chrono::high_resolution_clock::now();
        float generation_time = std::chrono::duration<float, std::milli>(end_time - start_time).count();
        
        LOGI("✓ Audio generated: %.1fs, %zu samples in %.2fms",
             audio.size() / (float)params.sample_rate, audio.size(), generation_time);
        
        return {
            .audio_data = audio,
            .sample_rate = params.sample_rate,
            .generation_time_ms = generation_time,
            .success = true,
            .error_message = ""
        };
        
    } catch (const std::exception& e) {
        LOGE("Generation failed: %s", e.what());
        return {
            .success = false,
            .error_message = std::string("Exception: ") + e.what()
        };
    }
}

std::vector<float> AudioGenEngine::encode_prompt(const std::string& prompt) {
    // Get input tensor
    TfLiteTensor* input_tensor = conditioner_->interpreter->input_tensor(0);
    
    // For simplicity, we tokenize the prompt as character indices
    // In production, use a proper tokenizer (e.g., CLIP tokenizer)
    int max_length = input_tensor->dims->data[1];
    float* input_data = input_tensor->data.f;
    std::fill_n(input_data, max_length, 0.0f);
    
    for (size_t i = 0; i < std::min(prompt.size(), size_t(max_length)); ++i) {
        input_data[i] = static_cast<float>(prompt[i]) / 127.0f; // Normalize to [-1, 1]
    }
    
    // Run inference
    if (conditioner_->interpreter->Invoke() != kTfLiteOk) {
        LOGE("Failed to invoke conditioner");
        throw std::runtime_error("Conditioner inference failed");
    }
    
    // Get output embedding
    TfLiteTensor* output_tensor = conditioner_->interpreter->output_tensor(0);
    int embedding_size = output_tensor->dims->data[1];
    
    std::vector<float> conditioning(embedding_size);
    std::copy_n(output_tensor->data.f, embedding_size, conditioning.begin());
    
    return conditioning;
}

std::vector<float> AudioGenEngine::diffusion_loop(
    const std::vector<float>& conditioning,
    int num_steps,
    float guidance_scale,
    ProgressCallback callback
) {
    // Initialize random latent
    TfLiteTensor* input_tensor = dit_->interpreter->input_tensor(0);
    int latent_size = 1;
    for (int i = 0; i < input_tensor->dims->size; ++i) {
        latent_size *= input_tensor->dims->data[i];
    }
    
    std::vector<float> latent(latent_size);
    std::mt19937 rng(std::random_device{}());
    std::normal_distribution<float> dist(0.0f, 1.0f);
    std::generate(latent.begin(), latent.end(), [&]() { return dist(rng); });
    
    // Get noise schedule
    auto sigmas = get_karras_sigmas(num_steps);
    
    // Diffusion steps
    for (int step = 0; step < num_steps; ++step) {
        if (cancel_requested_) break;
        
        if (callback) {
            callback(step, num_steps, "Diffusing step " + std::to_string(step + 1));
        }
        
        // Add noise at current sigma
        auto noisy_latent = add_noise(latent, sigmas[step]);
        
        // Copy to input tensor
        float* input_data = dit_->interpreter->input_tensor(0)->data.f;
        std::copy(noisy_latent.begin(), noisy_latent.end(), input_data);
        
        // Copy conditioning
        float* cond_data = dit_->interpreter->input_tensor(1)->data.f;
        std::copy(conditioning.begin(), conditioning.end(), cond_data);
        
        // Run DiT inference
        if (dit_->interpreter->Invoke() != kTfLiteOk) {
            LOGE("Failed to invoke DiT at step %d", step);
            throw std::runtime_error("DiT inference failed");
        }
        
        // Get predicted noise
        float* output_data = dit_->interpreter->output_tensor(0)->data.f;
        
        // Update latent (simplified Euler step)
        float dt = (step < num_steps - 1) ? (sigmas[step + 1] - sigmas[step]) : -sigmas[step];
        for (size_t i = 0; i < latent.size(); ++i) {
            latent[i] = latent[i] - dt * output_data[i];
        }
    }
    
    return latent;
}

std::vector<float> AudioGenEngine::decode_latent(const std::vector<float>& latent) {
    // Copy latent to input tensor
    float* input_data = autoencoder_->interpreter->input_tensor(0)->data.f;
    std::copy(latent.begin(), latent.end(), input_data);
    
    // Run decoder inference
    if (autoencoder_->interpreter->Invoke() != kTfLiteOk) {
        LOGE("Failed to invoke AutoEncoder");
        throw std::runtime_error("AutoEncoder inference failed");
    }
    
    // Get output audio
    TfLiteTensor* output_tensor = autoencoder_->interpreter->output_tensor(0);
    int audio_length = output_tensor->dims->data[1];
    
    std::vector<float> audio(audio_length);
    std::copy_n(output_tensor->data.f, audio_length, audio.begin());
    
    return audio;
}

std::vector<float> AudioGenEngine::get_karras_sigmas(int num_steps) {
    // Karras noise schedule: sigma_i = (sigma_max^(1/rho) + i/(n-1) * (sigma_min^(1/rho) - sigma_max^(1/rho)))^rho
    const float sigma_min = 0.02f;
    const float sigma_max = 80.0f;
    const float rho = 7.0f;
    
    std::vector<float> sigmas(num_steps + 1);
    for (int i = 0; i <= num_steps; ++i) {
        float t = static_cast<float>(i) / num_steps;
        float sigma = std::pow(
            std::pow(sigma_max, 1.0f / rho) + t * (std::pow(sigma_min, 1.0f / rho) - std::pow(sigma_max, 1.0f / rho)),
            rho
        );
        sigmas[i] = sigma;
    }
    
    return sigmas;
}

std::vector<float> AudioGenEngine::add_noise(const std::vector<float>& latent, float sigma) {
    std::vector<float> noisy(latent.size());
    std::mt19937 rng(std::random_device{}());
    std::normal_distribution<float> dist(0.0f, sigma);
    
    for (size_t i = 0; i < latent.size(); ++i) {
        noisy[i] = latent[i] + dist(rng);
    }
    
    return noisy;
}

void AudioGenEngine::cancel() {
    cancel_requested_ = true;
    LOGI("Generation cancelled");
}

} // namespace audiogen
