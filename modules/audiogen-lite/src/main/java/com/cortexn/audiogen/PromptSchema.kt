package com.cortexn.audiogen

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Schema for audio generation prompts
 * Supports structured prompts with metadata
 */
data class PromptSchema(
    @SerializedName("text")
    val text: String,
    
    @SerializedName("style")
    val style: AudioStyle? = null,
    
    @SerializedName("mood")
    val mood: List<String>? = null,
    
    @SerializedName("instruments")
    val instruments: List<String>? = null,
    
    @SerializedName("tempo")
    val tempo: Tempo? = null,
    
    @SerializedName("duration_seconds")
    val durationSeconds: Float? = null,
    
    @SerializedName("seed")
    val seed: Int? = null
) {
    /**
     * Audio style categories
     */
    enum class AudioStyle {
        @SerializedName("ambient")
        AMBIENT,
        
        @SerializedName("cinematic")
        CINEMATIC,
        
        @SerializedName("electronic")
        ELECTRONIC,
        
        @SerializedName("orchestral")
        ORCHESTRAL,
        
        @SerializedName("rock")
        ROCK,
        
        @SerializedName("jazz")
        JAZZ,
        
        @SerializedName("folk")
        FOLK,
        
        @SerializedName("experimental")
        EXPERIMENTAL
    }
    
    /**
     * Tempo specifications
     */
    enum class Tempo {
        @SerializedName("slow")
        SLOW,       // < 90 BPM
        
        @SerializedName("moderate")
        MODERATE,   // 90-120 BPM
        
        @SerializedName("fast")
        FAST,       // 120-160 BPM
        
        @SerializedName("very_fast")
        VERY_FAST   // > 160 BPM
    }
    
    /**
     * Convert to natural language prompt
     */
    fun toPromptText(): String {
        val parts = mutableListOf<String>()
        
        // Base text
        parts.add(text)
        
        // Add style
        style?.let {
            parts.add("in ${it.name.lowercase().replace('_', ' ')} style")
        }
        
        // Add mood
        mood?.let {
            if (it.isNotEmpty()) {
                parts.add("with ${it.joinToString(", ")} mood")
            }
        }
        
        // Add instruments
        instruments?.let {
            if (it.isNotEmpty()) {
                parts.add("featuring ${it.joinToString(", ")}")
            }
        }
        
        // Add tempo
        tempo?.let {
            parts.add("at ${it.name.lowercase().replace('_', ' ')} tempo")
        }
        
        return parts.joinToString(" ")
    }
    
    /**
     * Create from JSON string
     */
    companion object {
        private val gson = Gson()
        
        fun fromJson(json: String): PromptSchema {
            return gson.fromJson(json, PromptSchema::class.java)
        }
        
        /**
         * Example prompts
         */
        fun examples(): List<PromptSchema> {
            return listOf(
                PromptSchema(
                    text = "peaceful piano melody",
                    style = AudioStyle.AMBIENT,
                    mood = listOf("calm", "relaxing"),
                    instruments = listOf("piano"),
                    tempo = Tempo.SLOW,
                    durationSeconds = 10.0f
                ),
                PromptSchema(
                    text = "energetic electronic beat",
                    style = AudioStyle.ELECTRONIC,
                    mood = listOf("energetic", "uplifting"),
                    tempo = Tempo.FAST,
                    durationSeconds = 10.0f
                ),
                PromptSchema(
                    text = "dramatic orchestral theme",
                    style = AudioStyle.ORCHESTRAL,
                    mood = listOf("epic", "dramatic"),
                    instruments = listOf("strings", "brass", "percussion"),
                    tempo = Tempo.MODERATE,
                    durationSeconds = 15.0f
                )
            )
        }
    }
}

/**
 * Example usage and testing
 */
fun main() {
    // Create structured prompt
    val prompt = PromptSchema(
        text = "atmospheric soundscape",
        style = PromptSchema.AudioStyle.AMBIENT,
        mood = listOf("mysterious", "ethereal"),
        tempo = PromptSchema.Tempo.SLOW,
        durationSeconds = 10.0f
    )
    
    // Convert to natural language
    val promptText = prompt.toPromptText()
    println("Generated prompt: $promptText")
    
    // Use with AudioGen
    val params = AudioGen.GenerationParams(
        prompt = promptText,
        durationSeconds = prompt.durationSeconds ?: 10.0f,
        seed = prompt.seed ?: -1
    )
    
    println("Generation params: $params")
}
