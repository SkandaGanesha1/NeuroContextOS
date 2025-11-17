package com.cortexn.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cortexn.app.viewmodels.AudioBufferViewModel

/**
 * Buffer Meter - Real-time audio buffer level visualization
 * 
 * Displays:
 * - Buffer fill percentage
 * - Underrun warnings
 * - Visual waveform
 */
@Composable
fun BufferMeter(
    viewModel: AudioBufferViewModel = viewModel()
) {
    val bufferLevel by viewModel.bufferLevel.collectAsState()
    val isUnderrun by viewModel.isUnderrun.collectAsState()
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Audio Buffer",
                style = MaterialTheme.typography.labelMedium
            )
            
            Text(
                text = "${(bufferLevel * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = if (isUnderrun) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }
        
        BufferLevelBar(bufferLevel, isUnderrun)
        
        if (isUnderrun) {
            Text(
                text = "⚠️ Buffer underrun - audio may skip",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun BufferLevelBar(level: Float, isUnderrun: Boolean) {
    val animatedLevel by animateFloatAsState(
        targetValue = level,
        animationSpec = tween(durationMillis = 100)
    )
    
    val barColor = if (isUnderrun) {
        Color(0xFFF44336)
    } else if (level < 0.3f) {
        Color(0xFFFFC107)
    } else {
        Color(0xFF4CAF50)
    }
    
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
    ) {
        val width = size.width
        val height = size.height
        
        // Background
        drawLine(
            color = Color.LightGray,
            start = Offset(0f, height / 2),
            end = Offset(width, height / 2),
            strokeWidth = height,
            cap = StrokeCap.Round
        )
        
        // Level
        drawLine(
            color = barColor,
            start = Offset(0f, height / 2),
            end = Offset(width * animatedLevel, height / 2),
            strokeWidth = height,
            cap = StrokeCap.Round
        )
    }
}
