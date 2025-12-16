package com.playdeca.jmedia.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.playdeca.jmedia.ui.components.BottomNavigationBar
import com.playdeca.jmedia.ui.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    navController: NavController,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        bottomBar = {
            BottomNavigationBar(
                currentRoute = "player",
                onNavigate = { route -> navController.navigate(route) }
            )
        }
    ) { paddingValues ->
        if (uiState.currentSong != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Album Art Placeholder
                Card(
                    modifier = Modifier
                        .size(300.dp)
                        .padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "Album Art",
                            modifier = Modifier.size(100.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Song Info
                Text(
                    text = uiState.currentSong!!.title,
                    style = MaterialTheme.typography.headlineMedium
                )
                
                Text(
                    text = "${uiState.currentSong!!.artist} • ${uiState.currentSong!!.album}",
                    style = MaterialTheme.typography.bodyLarge
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // Progress Bar
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Slider(
                        value = uiState.position.toFloat(),
                        onValueChange = { viewModel.seekTo(it.toLong()) },
                        valueRange = 0f..uiState.duration.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDuration(uiState.position),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = formatDuration(uiState.duration),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // Playback Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.toggleShuffle() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (uiState.isShuffled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    
                    IconButton(
                        onClick = { viewModel.previous() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous"
                        )
                    }
                    
                    FloatingActionButton(
                        onClick = { viewModel.togglePlayPause() }
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "Pause" else "Play"
                        )
                    }
                    
                    IconButton(
                        onClick = { viewModel.next() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next"
                        )
                    }
                    
                    IconButton(
                        onClick = { viewModel.toggleRepeat() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Repeat,
                            contentDescription = "Repeat",
                            tint = if (uiState.isRepeating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // Volume Control
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeDown,
                        contentDescription = "Volume Down"
                    )
                    
                    Slider(
                        value = uiState.volume,
                        onValueChange = { viewModel.setVolume(it) },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f)
                    )
                    
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Volume Up"
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Queue Button
                Button(
                    onClick = { navController.navigate("queue") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue")
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No song currently playing")
            }
        }
    }
}

private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return String.format("%d:%02d", minutes, remainingSeconds)
}