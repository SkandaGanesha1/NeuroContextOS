package com.cortexn.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cortexn.app.policy.PromptSchemas
import com.cortexn.app.ui.components.BufferMeter
import com.cortexn.app.viewmodels.FocusViewModel
import timber.log.Timber

/**
 * Focus Screen - Audio generation for concentration
 * 
 * Features:
 * - Preset mood selection
 * - Custom prompt input
 * - Real-time audio playback
 * - Buffer level visualization
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(
    viewModel: FocusViewModel = viewModel()
) {
    val context = LocalContext.current
    val isGenerating by viewModel.isGenerating.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPrompt by viewModel.currentPrompt.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Focus Mode") },
                actions = {
                    if (isPlaying) {
                        IconButton(onClick = { viewModel.stopPlayback() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Card
            StatusCard(isGenerating, isPlaying)
            
            // Preset Moods
            Text(
                text = "Select Mood",
                style = MaterialTheme.typography.titleMedium
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MoodButton(
                    label = "Focus",
                    icon = Icons.Default.Psychology,
                    onClick = { viewModel.selectPreset(PromptSchemas.Templates.FOCUS_MUSIC) },
                    modifier = Modifier.weight(1f)
                )
                
                MoodButton(
                    label = "Relax",
                    icon = Icons.Default.SelfImprovement,
                    onClick = { viewModel.selectPreset(PromptSchemas.Templates.RELAX_MUSIC) },
                    modifier = Modifier.weight(1f)
                )
                
                MoodButton(
                    label = "Energize",
                    icon = Icons.Default.Bolt,
                    onClick = { viewModel.selectPreset(PromptSchemas.Templates.ENERGIZE_MUSIC) },
                    modifier = Modifier.weight(1f)
                )
            }
            
            // Current Prompt Display
            if (currentPrompt != null) {
                PromptCard(currentPrompt!!)
            }
            
            // Buffer Meter
            if (isPlaying) {
                BufferMeter()
            }
            
            Spacer(Modifier.weight(1f))
            
            // Generate Button
            Button(
                onClick = { viewModel.generateAudio() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isGenerating && currentPrompt != null
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isGenerating) "Generating..." else "Generate Audio")
            }
        }
    }
}

@Composable
fun StatusCard(isGenerating: Boolean, isPlaying: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = when {
                        isGenerating -> "Generating audio..."
                        isPlaying -> "Playing"
                        else -> "Ready"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
            
            if (isGenerating || isPlaying) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
fun MoodButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = label)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun PromptCard(prompt: PromptSchemas.AudioGenPrompt) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Current Prompt",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Text(
                text = prompt.text,
                style = MaterialTheme.typography.bodyMedium
            )
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text("${prompt.duration.toInt()}s") }
                )
                
                AssistChip(
                    onClick = {},
                    label = { Text(prompt.style.name) }
                )
                
                prompt.mood.firstOrNull()?.let { mood ->
                    AssistChip(
                        onClick = {},
                        label = { Text(mood) }
                    )
                }
            }
        }
    }
}
