package com.cortexn.app.policy

import com.google.gson.annotations.SerializedName

/**
 * Prompt schemas for agents
 */
object PromptSchemas {
    
    /**
     * AudioGen prompt schema
     */
    data class AudioGenPrompt(
        @SerializedName("text")
        val text: String,
        
        @SerializedName("mood")
        val mood: List<String> = emptyList(),
        
        @SerializedName("style")
        val style: AudioStyle = AudioStyle.AMBIENT,
        
        @SerializedName("duration")
        val duration: Float = 10f,
        
        @SerializedName("tempo")
        val tempo: Tempo = Tempo.MODERATE,
        
        @SerializedName("instruments")
        val instruments: List<String> = emptyList()
    )
    
    enum class AudioStyle {
        @SerializedName("ambient")
        AMBIENT,
        
        @SerializedName("electronic")
        ELECTRONIC,
        
        @SerializedName("orchestral")
        ORCHESTRAL,
        
        @SerializedName("cinematic")
        CINEMATIC
    }
    
    enum class Tempo {
        @SerializedName("slow")
        SLOW,
        
        @SerializedName("moderate")
        MODERATE,
        
        @SerializedName("fast")
        FAST
    }
    
    /**
     * Vision agent prompt schema
     */
    data class VisionPrompt(
        @SerializedName("task")
        val task: VisionTask,
        
        @SerializedName("confidence_threshold")
        val confidenceThreshold: Float = 0.5f,
        
        @SerializedName("target_classes")
        val targetClasses: List<String> = emptyList()
    )
    
    enum class VisionTask {
        @SerializedName("detect")
        DETECT,
        
        @SerializedName("track")
        TRACK,
        
        @SerializedName("segment")
        SEGMENT
    }
    
    /**
     * Predefined prompt templates
     */
    object Templates {
        val FOCUS_MUSIC = AudioGenPrompt(
            text = "ambient atmospheric soundscape with gentle piano and soft synthesizers",
            mood = listOf("calm", "focused", "peaceful"),
            style = AudioStyle.AMBIENT,
            duration = 600f,
            tempo = Tempo.SLOW
        )
        
        val ENERGIZE_MUSIC = AudioGenPrompt(
            text = "upbeat electronic music with rhythmic beats and energetic melody",
            mood = listOf("energetic", "motivated", "uplifting"),
            style = AudioStyle.ELECTRONIC,
            duration = 300f,
            tempo = Tempo.FAST
        )
        
        val RELAX_MUSIC = AudioGenPrompt(
            text = "calming nature sounds with soft instrumental background",
            mood = listOf("relaxed", "serene", "meditative"),
            style = AudioStyle.AMBIENT,
            duration = 900f,
            tempo = Tempo.SLOW,
            instruments = listOf("piano", "strings", "nature")
        )
    }
}
