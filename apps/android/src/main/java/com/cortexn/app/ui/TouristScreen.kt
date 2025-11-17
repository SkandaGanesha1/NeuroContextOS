package com.cortexn.app.ui

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cortexn.app.viewmodels.TouristViewModel

/**
 * Tourist Screen - Vision-based navigation assistant
 * 
 * Features:
 * - Real-time object detection via camera
 * - Landmark recognition
 * - Scene description
 * - Accessibility features
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TouristScreen(
    viewModel: TouristViewModel = viewModel()
) {
    val context = LocalContext.current
    val isActive by viewModel.isActive.collectAsState()
    val detections by viewModel.detections.collectAsState()
    val sceneDescription by viewModel.sceneDescription.collectAsState()
    
    DisposableEffect(Unit) {
        viewModel.initializeCamera(context)
        onDispose {
            viewModel.shutdown()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tourist - Vision Assistant") },
                actions = {
                    IconButton(
                        onClick = { 
                            if (isActive) viewModel.stopDetection() 
                            else viewModel.startDetection() 
                        }
                    ) {
                        Icon(
                            if (isActive) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            contentDescription = if (isActive) "Stop" else "Start"
                        )
                    }
                    
                    IconButton(onClick = { viewModel.captureAndDescribe() }) {
                        Icon(Icons.Default.Description, contentDescription = "Describe")
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
            // Camera Preview
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onPreviewReady = { previewView ->
                    viewModel.bindCameraPreview(previewView)
                }
            )
            
            // Detection Overlay
            if (isActive && detections.isNotEmpty()) {
                DetectionOverlay(
                    detections = detections,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // Scene Description Bottom Sheet
            if (sceneDescription != null) {
                SceneDescriptionCard(
                    description = sceneDescription!!,
                    onDismiss = { viewModel.clearDescription() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
            
            // Detection Stats
            if (isActive) {
                DetectionStatsCard(
                    detectionCount = detections.size,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                )
            }
        }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onPreviewReady: (PreviewView) -> Unit
) {
    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                onPreviewReady(this)
            }
        },
        modifier = modifier
    )
}

@Composable
fun DetectionOverlay(
    detections: List<TouristViewModel.Detection>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        detections.forEach { detection ->
            val bbox = detection.boundingBox
            
            // Draw bounding box
            drawRect(
                color = Color.Green,
                topLeft = Offset(bbox.x1 * size.width, bbox.y1 * size.height),
                size = Size(
                    (bbox.x2 - bbox.x1) * size.width,
                    (bbox.y2 - bbox.y1) * size.height
                ),
                style = Stroke(width = 3f)
            )
            
            // Label background
            val labelWidth = 150f
            val labelHeight = 30f
            drawRect(
                color = Color.Green,
                topLeft = Offset(bbox.x1 * size.width, bbox.y1 * size.height - labelHeight),
                size = Size(labelWidth, labelHeight)
            )
        }
    }
}

@Composable
fun SceneDescriptionCard(
    description: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
                    text = "Scene Description",
                    style = MaterialTheme.typography.titleMedium
                )
                
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium
            )
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { /* Text-to-speech */ },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Speak")
                }
                
                OutlinedButton(
                    onClick = { /* Share */ },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
            }
        }
    }
}

@Composable
fun DetectionStatsCard(
    detectionCount: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = "Detections",
                tint = MaterialTheme.colorScheme.primary
            )
            
            Column {
                Text(
                    text = "$detectionCount",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "detected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
