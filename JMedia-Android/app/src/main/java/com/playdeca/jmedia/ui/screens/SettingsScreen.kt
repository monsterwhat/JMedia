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
import com.playdeca.jmedia.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            BottomNavigationBar(
                currentRoute = "settings",
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
            
            else -> {
                LazyColumn(
                    modifier = Modifier.padding(paddingValues),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    item {
                        Text(
                            text = "Profile",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    
                    items(uiState.profiles) { profile ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            onClick = { 
                                viewModel.switchToProfile(profile.id)
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    if (profile.isMainProfile) {
                                        Text(
                                            text = "Main Profile",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                
                                if (uiState.currentProfile?.id == profile.id) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Current Profile",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Library",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            onClick = { 
                                viewModel.scanLibrary()
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Scan Library"
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = "Scan Library",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                    
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            onClick = { 
                                viewModel.scanLibraryIncremental()
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Update,
                                    contentDescription = "Incremental Scan"
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = "Incremental Scan",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                    
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Server",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = "Server Information",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "URL: ${uiState.serverUrl}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Profile: ${uiState.currentProfile?.name ?: "None"}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}