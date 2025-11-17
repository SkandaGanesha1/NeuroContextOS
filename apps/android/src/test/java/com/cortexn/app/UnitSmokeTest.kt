package com.cortexn.app

import com.cortexn.app.policy.PromptSchemas
import com.cortexn.app.policy.SapiensPlanner
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit Smoke Tests
 * 
 * Basic unit tests to verify core functionality
 */
class UnitSmokeTest {
    
    @Test
    fun testPromptSchemasSerialization() {
        val prompt = PromptSchemas.AudioGenPrompt(
            text = "ambient calm music",
            mood = listOf("calm", "focused"),
            style = PromptSchemas.AudioStyle.AMBIENT,
            duration = 600f
        )
        
        assertNotNull(prompt)
        assertEquals("ambient calm music", prompt.text)
        assertEquals(PromptSchemas.AudioStyle.AMBIENT, prompt.style)
        assertEquals(600f, prompt.duration, 0.01f)
    }
    
    @Test
    fun testPromptTemplates() {
        val focusPrompt = PromptSchemas.Templates.FOCUS_MUSIC
        
        assertNotNull(focusPrompt)
        assertTrue(focusPrompt.mood.contains("calm"))
        assertEquals(PromptSchemas.AudioStyle.AMBIENT, focusPrompt.style)
    }
    
    @Test
    fun testBuildConfigKeys() {
        assertNotNull(BuildConfigKeys.MODEL_YOLO)
        assertNotNull(BuildConfigKeys.MODEL_WHISPER)
        assertNotNull(BuildConfigKeys.MODEL_LLAMA)
        
        assertTrue(BuildConfigKeys.DEFAULT_NUM_THREADS > 0)
        assertTrue(BuildConfigKeys.AUDIO_SAMPLE_RATE > 0)
    }
    
    @Test
    fun testAudioGenPromptDuration() {
        val prompt = PromptSchemas.AudioGenPrompt(
            text = "test",
            duration = 10f
        )
        
        assertTrue(prompt.duration > 0)
        assertTrue(prompt.duration <= 30f)  // Max 30 seconds for on-device
    }
    
    @Test
    fun testVisionPromptDefaults() {
        val prompt = PromptSchemas.VisionPrompt(
            task = PromptSchemas.VisionTask.DETECT
        )
        
        assertEquals(0.5f, prompt.confidenceThreshold, 0.01f)
        assertTrue(prompt.targetClasses.isEmpty())
    }
    
    @Test
    fun testSapiensIntents() {
        val intents = SapiensPlanner.Intent.values()
        
        assertTrue(intents.contains(SapiensPlanner.Intent.AUDIO_GENERATION))
        assertTrue(intents.contains(SapiensPlanner.Intent.OBJECT_DETECTION))
        assertTrue(intents.contains(SapiensPlanner.Intent.TRANSCRIPTION))
    }
}
