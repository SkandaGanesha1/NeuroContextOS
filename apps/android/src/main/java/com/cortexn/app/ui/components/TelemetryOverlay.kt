package com.cortexn.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cortexn.app.viewmodels.TelemetryViewModel

/**
 * Real-time telemetry overlay
 * 
 * Displays:
 * - P50/P95/P99 latency percentiles
 * - Energy per inference (Joules)
 * - Current power (Watts)
 * - Power rails breakdown (CPU, GPU, NPU, DRAM)
 * - Thermal state
 */
@Composable
fun TelemetryOverlay(
    modifier: Modifier = Modifier,
    viewModel: TelemetryViewModel = viewModel()
) {
    val telemetry by viewModel.telemetryData.collectAsState()
    
    AnimatedVisibility(
        visible = true,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    shape = MaterialTheme.shapes.medium
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "⚡ Telemetry",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            // Latency percentiles
            MetricRow("P50", "${telemetry.p50LatencyMs}ms", Color(0xFF4CAF50))
            MetricRow("P95", "${telemetry.p95LatencyMs}ms", Color(0xFFFFC107))
            MetricRow("P99", "${telemetry.p99LatencyMs}ms", Color(0xFFF44336))
            
            Spacer(Modifier.height(4.dp))
            
            // Energy and power
            MetricRow("Energy", "${"%.3f".format(telemetry.energyJoules)}J", Color(0xFF2196F3))
            MetricRow("Power", "${"%.2f".format(telemetry.powerWatts)}W", Color(0xFF9C27B0))
            
            Spacer(Modifier.height(4.dp))
            
            // Power rails
            Text(
                text = "Power Rails:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            telemetry.powerRails.forEach { (rail, watts) ->
                RailRow(rail, watts)
            }
            
            Spacer(Modifier.height(4.dp))
            
            // Thermal state
            Text(
                text = "Thermal: ${telemetry.thermalState}",
                style = MaterialTheme.typography.labelSmall,
                color = getThermalColor(telemetry.thermalState)
            )
        }
    }
}

@Composable
fun MetricRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}

@Composable
fun RailRow(rail: String, watts: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = rail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${"%.2f".format(watts)}W",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun getThermalColor(thermalState: String): Color {
    return when (thermalState) {
        "NONE", "LIGHT" -> Color(0xFF4CAF50)
        "MODERATE" -> Color(0xFFFFC107)
        "SEVERE", "CRITICAL" -> Color(0xFFF44336)
        else -> Color.Gray
    }
}
