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
import com.playdeca.jmedia.ui.viewmodel.QueueViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    navController: NavController,
    viewModel: QueueViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Queue") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearQueue() }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear Queue")
                    }
                }
            )
        },
        bottomBar = {
            BottomNavigationBar(
                currentRoute = "queue",
                onNavigate = { route -> navController.navigate(route) }
            )
        }
    ) { paddingValues ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            
            uiState.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Error: ${uiState.error}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            uiState.queue.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = "Empty Queue",
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Queue is empty",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = "Add songs to see them here",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            
            else -> {
                LazyColumn(
                    modifier = Modifier.padding(paddingValues),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(uiState.queue.withIndex().toList()) { (index, song) ->
                        QueueItem(
                            song = song,
                            index = index,
                            isCurrentlyPlaying = uiState.currentSongId == song.id,
                            onPlay = { viewModel.playFromQueue(index) },
                            onRemove = { viewModel.removeFromQueue(index) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueItem(
    song: com.playdeca.jmedia.data.model.Song,
    index: Int,
    isCurrentlyPlaying: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentlyPlaying) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Song number or play icon
            if (isCurrentlyPlaying) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Currently Playing",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Song info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isCurrentlyPlaying) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${song.artist} • ${song.durationFormatted}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrentlyPlaying) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Actions
            Row {
                IconButton(onClick = onPlay) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play"
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove from Queue"
                    )
                }
            }
        }
    }
}