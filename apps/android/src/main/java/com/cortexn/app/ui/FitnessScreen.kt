package com.cortexn.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cortexn.app.viewmodels.FitnessViewModel
import kotlin.math.sin

/**
 * Fitness Screen - SNN-based gesture recognition
 * 
 * Features:
 * - Real-time IMU capture
 * - Spiking Neural Network inference
 * - Gesture classification (tap, swipe, shake)
 * - Energy-efficient always-on detection
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FitnessScreen(
    viewModel: FitnessViewModel = viewModel()
) {
    val isActive by viewModel.isActive.collectAsState()
    val currentGesture by viewModel.currentGesture.collectAsState()
    val gestureHistory by viewModel.gestureHistory.collectAsState()
    val imuData by viewModel.imuData.collectAsState()
    val snnMetrics by viewModel.snnMetrics.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fitness - Gesture Recognition") },
                actions = {
                    IconButton(
                        onClick = { 
                            if (isActive) viewModel.stopDetection() 
                            else viewModel.startDetection() 
                        }
                    ) {
                        Icon(
                            if (isActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isActive) "Stop" else "Start"
                        )
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
            // Current Gesture Card
            CurrentGestureCard(currentGesture, isActive)
            
            // IMU Visualization
            Text(
                text = "IMU Sensor Data",
                style = MaterialTheme.typography.titleMedium
            )
            IMUVisualization(imuData)
            
            // SNN Metrics
            SNNMetricsCard(snnMetrics)
            
            // Gesture History
            Text(
                text = "Recent Gestures",
                style = MaterialTheme.typography.titleMedium
            )
            
            GestureHistoryList(gestureHistory)
        }
    }
}

@Composable
fun CurrentGestureCard(gesture: String?, isActive: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (gesture != null && gesture != "None") {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isActive) {
                Icon(
                    imageVector = getGestureIcon(gesture),
                    contentDescription = gesture,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.DoNotDisturb,
                    contentDescription = "Inactive",
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Text(
                text = if (isActive) (gesture ?: "Waiting...") else "Inactive",
                style = MaterialTheme.typography.headlineMedium
            )
            
            if (isActive && gesture != null && gesture != "None") {
                Text(
                    text = "Detected gesture",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun IMUVisualization(imuData: FitnessViewModel.IMUData) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Accelerometer",
                style = MaterialTheme.typography.labelMedium
            )
            
            Spacer(Modifier.height(8.dp))
            
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val width = size.width
                val height = size.height
                val centerY = height / 2
                
                // Draw center line
                drawLine(
                    color = Color.Gray,
                    start = Offset(0f, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = 1f
                )
                
                // Draw accelerometer traces
                val scale = height / 4f
                
                // X-axis (red)
                drawPath(
                    path = Path().apply {
                        imuData.accelX.forEachIndexed { index, value ->
                            val x = (index.toFloat() / imuData.accelX.size) * width
                            val y = centerY - (value * scale)
                            if (index == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    },
                    color = Color.Red,
                    style = Stroke(width = 2f)
                )
                
                // Y-axis (green)
                drawPath(
                    path = Path().apply {
                        imuData.accelY.forEachIndexed { index, value ->
                            val x = (index.toFloat() / imuData.accelY.size) * width
                            val y = centerY - (value * scale)
                            if (index == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    },
                    color = Color.Green,
                    style = Stroke(width = 2f)
                )
                
                // Z-axis (blue)
                drawPath(
                    path = Path().apply {
                        imuData.accelZ.forEachIndexed { index, value ->
                            val x = (index.toFloat() / imuData.accelZ.size) * width
                            val y = centerY - (value * scale)
                            if (index == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    },
                    color = Color.Blue,
                    style = Stroke(width = 2f)
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                LegendItem("X", Color.Red)
                LegendItem("Y", Color.Green)
                LegendItem("Z", Color.Blue)
            }
        }
    }
}

@Composable
fun SNNMetricsCard(metrics: FitnessViewModel.SNNMetrics) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "SNN Performance",
                style = MaterialTheme.typography.titleMedium
            )
            
            MetricRow("Inference Latency", "${metrics.latencyMs}ms")
            MetricRow("Energy/Inference", "${"%.3f".format(metrics.energyMicroJ)}µJ")
            MetricRow("Spike Rate", "${metrics.spikeRate}%")
            MetricRow("Accuracy", "${(metrics.accuracy * 100).toInt()}%")
        }
    }
}

@Composable
fun GestureHistoryList(history: List<FitnessViewModel.GestureRecord>) {
    if (history.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No gestures detected yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        history.take(5).forEach { record ->
            GestureHistoryItem(record)
        }
    }
}

@Composable
fun GestureHistoryItem(record: FitnessViewModel.GestureRecord) {
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = getGestureIcon(record.gesture),
                    contentDescription = record.gesture,
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Column {
                    Text(
                        text = record.gesture,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "${(record.confidence * 100).toInt()}% confidence",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Text(
                text = record.timestamp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun LegendItem(label: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(12.dp)) {
            drawCircle(color = color)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

fun getGestureIcon(gesture: String?): androidx.compose.ui.graphics.vector.ImageVector {
    return when (gesture?.lowercase()) {
        "tap" -> Icons.Default.TouchApp
        "swipe" -> Icons.Default.Swipe
        "shake" -> Icons.Default.Vibration
        "rotate" -> Icons.Default.RotateRight
        else -> Icons.Default.Sensors
    }
}
