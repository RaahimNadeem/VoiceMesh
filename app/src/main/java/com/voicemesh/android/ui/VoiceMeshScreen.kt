package com.voicemesh.android.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicemesh.android.model.EphemeralVoiceMessage
import com.voicemesh.android.model.VoicePeer
import kotlin.math.sin
import kotlin.math.PI

/**
 * Main VoiceMesh screen with dark terminal theme and Matrix green colors
 * Based on MVP specification for walkie-talkie style interface
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceMeshScreen(viewModel: VoiceMeshViewModel) {
    val serviceState by viewModel.serviceState.observeAsState()
    val recordingState by viewModel.recordingState.observeAsState()
    val playbackState by viewModel.playbackState.observeAsState()
    val connectedPeers by viewModel.connectedPeers.observeAsState(emptyList())
    val voiceMessages by viewModel.voiceMessages.observeAsState(emptyList())
    val selectedRecipient by viewModel.selectedRecipient.observeAsState()
    val recordedAudio by viewModel.recordedAudio.observeAsState()
    val errorMessage by viewModel.errorMessage.observeAsState()
    val networkState by viewModel.networkState.observeAsState()
    
    // Terminal theme colors
    val terminalBackground = Color(0xFF0D1117)
    val terminalGreen = Color(0xFF00FF41)
    val terminalDarkGreen = Color(0xFF008F11)
    val terminalGray = Color(0xFF21262D)
    val terminalLightGray = Color(0xFF484F58)
    
    var showPeerSelection by remember { mutableStateOf(false) }
    
    // Auto-refresh peers and messages
    LaunchedEffect(Unit) {
        viewModel.refreshPeers()
        viewModel.refreshMessages()
    }
    
    Scaffold(
        containerColor = terminalBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "> VoiceMesh",
                            fontFamily = FontFamily.Monospace,
                            color = terminalGreen,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        
                        // Network status indicator
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (networkState?.isActive == true) 
                                        terminalGreen 
                                    else 
                                        Color.Red
                                )
                        )
                    }
                },
                actions = {
                    // Peer count
                    Text(
                        text = "${connectedPeers.size} peers",
                        fontFamily = FontFamily.Monospace,
                        color = terminalGreen,
                        fontSize = 12.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = terminalGray,
                    titleContentColor = terminalGreen
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(terminalBackground)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // Service status
            ServiceStatusCard(
                serviceState = serviceState,
                networkState = networkState,
                terminalGreen = terminalGreen,
                terminalGray = terminalGray
            )
            
            // Error message
            errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.Red.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = error,
                            color = Color.Red,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear error",
                                tint = Color.Red
                            )
                        }
                    }
                }
            }
            
            // Recipient selection
            RecipientSelectionCard(
                selectedRecipient = selectedRecipient,
                connectedPeers = connectedPeers,
                onRecipientSelected = { viewModel.selectRecipient(it) },
                terminalGreen = terminalGreen,
                terminalGray = terminalGray
            )
            
            // Voice recording interface
            VoiceRecordingCard(
                recordingState = recordingState,
                recordedAudio = recordedAudio,
                selectedRecipient = selectedRecipient,
                onStartRecording = { viewModel.startRecording() },
                onStopRecording = { viewModel.stopRecording() },
                onCancelRecording = { viewModel.cancelRecording() },
                onSendMessage = { viewModel.sendRecordedMessage() },
                onClearRecording = { viewModel.clearRecordedAudio() },
                terminalGreen = terminalGreen,
                terminalDarkGreen = terminalDarkGreen,
                terminalGray = terminalGray
            )
            
            // Voice messages list
            VoiceMessagesCard(
                voiceMessages = voiceMessages,
                playbackState = playbackState,
                onPlayMessage = { viewModel.playVoiceMessage(it) },
                onStopPlayback = { viewModel.stopPlayback() },
                terminalGreen = terminalGreen,
                terminalGray = terminalGray,
                terminalLightGray = terminalLightGray
            )
        }
    }
}

@Composable
private fun ServiceStatusCard(
    serviceState: ServiceState?,
    networkState: NetworkState?,
    terminalGreen: Color,
    terminalGray: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = terminalGray),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "$ system status",
                fontFamily = FontFamily.Monospace,
                color = terminalGreen,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            val statusText = when (serviceState) {
                is ServiceState.INITIALIZING -> "Initializing VoiceMesh..."
                is ServiceState.ACTIVE -> "VoiceMesh ACTIVE - ${networkState?.status ?: ""}"
                is ServiceState.INACTIVE -> "VoiceMesh INACTIVE"
                is ServiceState.ERROR -> "ERROR: ${serviceState.message}"
                null -> "Unknown state"
            }
            
            Text(
                text = statusText,
                fontFamily = FontFamily.Monospace,
                color = terminalGreen,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun RecipientSelectionCard(
    selectedRecipient: VoicePeer?,
    connectedPeers: List<VoicePeer>,
    onRecipientSelected: (VoicePeer?) -> Unit,
    terminalGreen: Color,
    terminalGray: Color
) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = terminalGray),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "$ select recipient",
                fontFamily = FontFamily.Monospace,
                color = terminalGreen,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedRecipient?.nickname ?: "No recipient selected",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = terminalGreen,
                        unfocusedBorderColor = terminalGreen.copy(alpha = 0.5f),
                        focusedTextColor = terminalGreen,
                        unfocusedTextColor = terminalGreen.copy(alpha = 0.8f)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "None",
                                fontFamily = FontFamily.Monospace,
                                color = terminalGreen
                            )
                        },
                        onClick = {
                            onRecipientSelected(null)
                            expanded = false
                        }
                    )
                    
                    connectedPeers.forEach { peer ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    peer.nickname,
                                    fontFamily = FontFamily.Monospace,
                                    color = terminalGreen
                                )
                            },
                            onClick = {
                                onRecipientSelected(peer)
                                expanded = false
                            }
                        )
                    }
                }
            }
            
            if (connectedPeers.isEmpty()) {
                Text(
                    text = "No peers available",
                    fontFamily = FontFamily.Monospace,
                    color = terminalGreen.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun VoiceRecordingCard(
    recordingState: RecordingState?,
    recordedAudio: RecordedAudio?,
    selectedRecipient: VoicePeer?,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onSendMessage: () -> Unit,
    onClearRecording: () -> Unit,
    terminalGreen: Color,
    terminalDarkGreen: Color,
    terminalGray: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = terminalGray),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$ voice recorder",
                fontFamily = FontFamily.Monospace,
                color = terminalGreen,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Waveform visualization
            if (recordingState?.isRecording == true) {
                WaveformVisualization(
                    level = recordingState.currentLevel,
                    terminalGreen = terminalGreen,
                    modifier = Modifier.height(60.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = recordingState.getFormattedDuration(),
                    fontFamily = FontFamily.Monospace,
                    color = terminalGreen,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = "remaining: ${recordingState.getFormattedRemaining()}",
                    fontFamily = FontFamily.Monospace,
                    color = terminalGreen.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onStopRecording,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = terminalGreen,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "STOP",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    OutlinedButton(
                        onClick = onCancelRecording,
                        border = BorderStroke(1.dp, terminalGreen),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Cancel, contentDescription = "Cancel", tint = terminalGreen)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "CANCEL",
                            color = terminalGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else if (recordedAudio != null) {
                // Show recorded audio
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.AudioFile,
                        contentDescription = "Recorded audio",
                        tint = terminalGreen,
                        modifier = Modifier.size(48.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Recording ready",
                        fontFamily = FontFamily.Monospace,
                        color = terminalGreen,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        text = "${recordedAudio.getFormattedDuration()} • ${recordedAudio.getSizeKB()}KB",
                        fontFamily = FontFamily.Monospace,
                        color = terminalGreen.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = onSendMessage,
                            enabled = selectedRecipient != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = terminalGreen,
                                contentColor = Color.Black,
                                disabledContainerColor = terminalGreen.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "SEND",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        OutlinedButton(
                            onClick = onClearRecording,
                            border = BorderStroke(1.dp, terminalGreen),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear", tint = terminalGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "CLEAR",
                                color = terminalGreen,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                // Show record button
                Button(
                    onClick = onStartRecording,
                    enabled = selectedRecipient != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = terminalDarkGreen,
                        contentColor = terminalGreen,
                        disabledContainerColor = terminalGray
                    ),
                    shape = CircleShape,
                    modifier = Modifier.size(120.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Record",
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            "RECORD",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                if (selectedRecipient == null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Select recipient to record",
                        fontFamily = FontFamily.Monospace,
                        color = terminalGreen.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun WaveformVisualization(
    level: Float,
    terminalGreen: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.fillMaxWidth()
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2
        
        // Draw animated waveform based on audio level
        val barCount = 40
        val barWidth = width / barCount
        
        for (i in 0 until barCount) {
            val x = i * barWidth
            val animatedLevel = level * (1 + 0.3f * sin(i * 0.5f + System.currentTimeMillis() * 0.01f)).toFloat()
            val barHeight = (height * 0.1f) + (height * 0.8f * animatedLevel)
            
            drawRect(
                color = terminalGreen,
                topLeft = androidx.compose.ui.geometry.Offset(x, centerY - barHeight / 2),
                size = androidx.compose.ui.geometry.Size(barWidth * 0.8f, barHeight)
            )
        }
    }
}

@Composable
private fun VoiceMessagesCard(
    voiceMessages: List<EphemeralVoiceMessage>,
    playbackState: PlaybackState?,
    onPlayMessage: (String) -> Unit,
    onStopPlayback: () -> Unit,
    terminalGreen: Color,
    terminalGray: Color,
    terminalLightGray: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = terminalGray),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "$ voice messages",
                fontFamily = FontFamily.Monospace,
                color = terminalGreen,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (voiceMessages.isEmpty()) {
                Text(
                    text = "No voice messages",
                    fontFamily = FontFamily.Monospace,
                    color = terminalGreen.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            } else {
                LazyColumn(
                    modifier = Modifier.height(200.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(voiceMessages) { message ->
                        VoiceMessageItem(
                            message = message,
                            isPlaying = playbackState?.isPlaying == true,
                            onPlayMessage = onPlayMessage,
                            onStopPlayback = onStopPlayback,
                            terminalGreen = terminalGreen,
                            terminalLightGray = terminalLightGray
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceMessageItem(
    message: EphemeralVoiceMessage,
    isPlaying: Boolean,
    onPlayMessage: (String) -> Unit,
    onStopPlayback: () -> Unit,
    terminalGreen: Color,
    terminalLightGray: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = terminalLightGray),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "From: ${message.senderID}",
                    fontFamily = FontFamily.Monospace,
                    color = terminalGreen,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Text(
                    text = "${message.getEstimatedDurationSeconds()}s • ${(message.audioData.size / 1024)}KB",
                    fontFamily = FontFamily.Monospace,
                    color = terminalGreen.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
                
                Text(
                    text = "Expires in: ${message.remainingTimeMs() / 1000}s",
                    fontFamily = FontFamily.Monospace,
                    color = terminalGreen.copy(alpha = 0.5f),
                    fontSize = 10.sp
                )
            }
            
            IconButton(
                onClick = {
                    if (isPlaying) {
                        onStopPlayback()
                    } else {
                        onPlayMessage(message.id)
                    }
                }
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Stop" else "Play",
                    tint = terminalGreen
                )
            }
        }
    }
}

// Preview functions for Android Studio Design panel
@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
fun VoiceMeshScreenPreview() {
    // Mock ViewModel for preview
    val mockViewModel = object : VoiceMeshViewModel() {
        init {
            // Initialize with sample data for preview
        }
    }
    
    VoiceMeshScreen(viewModel = mockViewModel)
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
fun RecordButtonPreview() {
    val terminalGreen = Color(0xFF00FF41)
    val terminalDarkGreen = Color(0xFF008F11)
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(16.dp)
    ) {
        // Record button preview
        Button(
            onClick = { },
            colors = ButtonDefaults.buttonColors(
                containerColor = terminalDarkGreen,
                contentColor = terminalGreen
            ),
            shape = CircleShape,
            modifier = Modifier.size(120.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Record",
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    "RECORD",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
fun TerminalThemePreview() {
    val terminalBackground = Color(0xFF0D1117)
    val terminalGreen = Color(0xFF00FF41)
    val terminalGray = Color(0xFF21262D)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = terminalGray),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "> VoiceMesh Terminal Interface",
                fontFamily = FontFamily.Monospace,
                color = terminalGreen,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "$ system status: ACTIVE",
                fontFamily = FontFamily.Monospace,
                color = terminalGreen,
                fontSize = 12.sp
            )
            
            Text(
                text = "$ connected peers: 3",
                fontFamily = FontFamily.Monospace,
                color = terminalGreen,
                fontSize = 12.sp
            )
            
            Text(
                text = "$ mesh network: ONLINE",
                fontFamily = FontFamily.Monospace,
                color = terminalGreen,
                fontSize = 12.sp
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
fun VoiceMessagePreview() {
    val terminalGreen = Color(0xFF00FF41)
    val terminalGray = Color(0xFF21262D)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = terminalGray),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Voice message from Alice",
                    fontFamily = FontFamily.Monospace,
                    color = terminalGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Duration: 15s • Size: 60KB",
                    fontFamily = FontFamily.Monospace,
                    color = terminalGreen.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
                Text(
                    text = "Expires in: 4:23",
                    fontFamily = FontFamily.Monospace,
                    color = Color.Yellow,
                    fontSize = 10.sp
                )
            }
            
            IconButton(onClick = { }) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = terminalGreen,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
} 