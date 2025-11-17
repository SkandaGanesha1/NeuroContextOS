package com.cortexn.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cortexn.app.BuildConfig
import com.cortexn.app.ui.components.TelemetryOverlay
import com.cortexn.app.viewmodels.HomeViewModel

/**
 * Home Screen - Main dashboard
 * 
 * Features:
 * - System status overview
 * - Telemetry overlay (p99 latency, energy, power rails)
 * - Agent status cards
 * - Federated learning status
 * - Quick actions
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel()
) {
    val context = LocalContext.current
    val systemStatus by viewModel.systemStatus.collectAsState()
    val telemetryEnabled by viewModel.telemetryEnabled.collectAsState()
    
    var showTelemetry by remember { mutableStateOf(BuildConfig.ENABLE_TELEMETRY) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cortex-N × SAPIENT × AURA") },
                actions = {
                    IconButton(onClick = { showTelemetry = !showTelemetry }) {
                        Icon(
                            imageVector = if (showTelemetry) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle Telemetry"
                        )
                    }
                    IconButton(onClick = { viewModel.refreshStatus() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // System Status Card
                item {
                    SystemStatusCard(systemStatus)
                }
                
                // Agent Status Cards
                item {
                    Text(
                        text = "Active Agents",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                
                items(systemStatus.agents) { agent ->
                    AgentStatusCard(agent)
                }
                
                // Federated Learning Status
                if (BuildConfig.ENABLE_FL) {
                    item {
                        FederatedLearningCard(systemStatus.flStatus)
                    }
                }
                
                // Quick Actions
                item {
                    QuickActionsCard(
                        onStartAudioGen = { viewModel.startAudioGeneration() },
                        onStartVision = { viewModel.startVisionDetection() },
                        onStartGesture = { viewModel.startGestureRecognition() }
                    )
                }
            }
            
            // Telemetry Overlay
            if (showTelemetry && telemetryEnabled) {
                TelemetryOverlay(
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }
        }
    }
}

@Composable
fun SystemStatusCard(status: HomeViewModel.SystemStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "System Status",
                    style = MaterialTheme.typography.titleMedium
                )
                
                Surface(
                    color = if (status.healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = if (status.healthy) "Healthy" else "Degraded",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            
            Divider()
            
            StatusRow("AURA Router", status.auraStatus)
            StatusRow("Power Profiler", status.profilerStatus)
            StatusRow("FL Gossip", status.flStatus)
            
            Text(
                text = "Uptime: ${status.uptimeMinutes} minutes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatusRow(label: String, status: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = when (status) {
                "Active" -> MaterialTheme.colorScheme.primary
                "Idle" -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.error
            }
        )
    }
}

@Composable
fun AgentStatusCard(agent: HomeViewModel.AgentStatus) {
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
                    imageVector = agent.icon,
                    contentDescription = agent.name,
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Column {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = agent.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            if (agent.isActive) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
fun FederatedLearningCard(status: String) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Federated Learning"
                )
                Text(
                    text = "Federated Learning",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            
            Text(
                text = "Status: $status",
                style = MaterialTheme.typography.bodyMedium
            )
            
            Text(
                text = "Privacy-preserving model updates via WiFi Direct P2P",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun QuickActionsCard(
    onStartAudioGen: () -> Unit,
    onStartVision: () -> Unit,
    onStartGesture: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Quick Actions",
                style = MaterialTheme.typography.titleMedium
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartAudioGen,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Audio")
                }
                
                Button(
                    onClick = onStartVision,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Camera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Vision")
                }
            }
            
            Button(
                onClick = onStartGesture,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Waving, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Gesture Recognition")
            }
        }
    }
}
