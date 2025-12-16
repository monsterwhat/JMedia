package com.playdeca.jmedia.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.playdeca.jmedia.ui.components.BottomNavigationBar
import com.playdeca.jmedia.ui.components.MiniPlayer
import com.playdeca.jmedia.ui.viewmodel.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    navController: NavController,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        bottomBar = {
            Column {
                uiState.currentSong?.let { song ->
                    MiniPlayer(
                        song = song,
                        isPlaying = uiState.isPlaying,
                        onPlayPause = { viewModel.togglePlayPause() },
                        onClick = { navController.navigate("player") }
                    )
                }
                BottomNavigationBar(
                    currentRoute = "library",
                    onNavigate = { route -> navController.navigate(route) }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(
                text = "Music Library",
                style = MaterialTheme.typography.headlineMedium
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
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
                    Text(
                        text = "Error: ${uiState.error}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                
                else -> {
                    LazyColumn {
                        item {
                            Text(
                                text = "Playlists",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        
                        items(uiState.playlists) { playlist ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                onClick = { 
                                    // Navigate to playlist details
                                }
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    playlist.description?.let { description ->
                                        Text(
                                            text = description,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Text(
                                        text = "${playlist.songCount} songs",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "All Songs",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        
                        items(uiState.songs) { song ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                onClick = { 
                                    viewModel.selectSong(song.id)
                                }
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "${song.artist} • ${song.album}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = song.durationFormatted,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}