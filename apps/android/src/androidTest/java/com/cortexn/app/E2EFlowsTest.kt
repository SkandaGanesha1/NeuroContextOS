package com.cortexn.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-End Flow Tests
 * 
 * Tests complete user workflows:
 * - Home screen navigation
 * - Focus mode audio generation
 * - Fitness gesture detection
 * - Tourist vision detection
 */
@RunWith(AndroidJUnit4::class)
class E2EFlowsTest {
    
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()
    
    @Test
    fun testHomeScreenDisplayed() {
        // Verify home screen is displayed
        composeTestRule.onNodeWithText("Cortex-N × SAPIENT × AURA").assertIsDisplayed()
        composeTestRule.onNodeWithText("System Status").assertIsDisplayed()
    }
    
    @Test
    fun testNavigationBetweenScreens() {
        // Navigate to Focus screen
        composeTestRule.onNodeWithText("Focus").performClick()
        composeTestRule.onNodeWithText("Focus Mode").assertIsDisplayed()
        
        // Navigate to Fitness screen
        composeTestRule.onNodeWithText("Fitness").performClick()
        composeTestRule.onNodeWithText("Fitness - Gesture Recognition").assertIsDisplayed()
        
        // Navigate to Tourist screen
        composeTestRule.onNodeWithText("Tourist").performClick()
        composeTestRule.onNodeWithText("Tourist - Vision Assistant").assertIsDisplayed()
        
        // Navigate back to Home
        composeTestRule.onNodeWithText("Home").performClick()
        composeTestRule.onNodeWithText("System Status").assertIsDisplayed()
    }
    
    @Test
    fun testFocusModeWorkflow() {
        // Navigate to Focus screen
        composeTestRule.onNodeWithText("Focus").performClick()
        
        // Select a mood preset
        composeTestRule.onNodeWithText("Focus").performClick()
        
        // Verify prompt is displayed
        composeTestRule.onNodeWithText("Current Prompt").assertIsDisplayed()
        
        // Attempt to generate (may require permissions)
        composeTestRule.onNodeWithText("Generate Audio").performClick()
    }
    
    @Test
    fun testFitnessGestureDetection() {
        // Navigate to Fitness screen
        composeTestRule.onNodeWithText("Fitness").performClick()
        
        // Verify IMU visualization is present
        composeTestRule.onNodeWithText("IMU Sensor Data").assertIsDisplayed()
        
        // Start detection
        composeTestRule.onNodeWithContentDescription("Start").performClick()
        
        // Verify detection is active
        composeTestRule.onNodeWithText("Waiting...").assertExists()
    }
    
    @Test
    fun testTelemetryOverlay() {
        // Verify telemetry overlay can be toggled
        composeTestRule.onNodeWithContentDescription("Toggle Telemetry").performClick()
        
        // Wait for animation
        composeTestRule.waitForIdle()
        
        // Toggle back
        composeTestRule.onNodeWithContentDescription("Toggle Telemetry").performClick()
    }
    
    @Test
    fun testQuickActions() {
        // Test quick action buttons on home screen
        composeTestRule.onNodeWithText("Audio").performClick()
        composeTestRule.waitForIdle()
        
        composeTestRule.onNodeWithText("Vision").performClick()
        composeTestRule.waitForIdle()
        
        composeTestRule.onNodeWithText("Gesture Recognition").performClick()
        composeTestRule.waitForIdle()
    }
}
