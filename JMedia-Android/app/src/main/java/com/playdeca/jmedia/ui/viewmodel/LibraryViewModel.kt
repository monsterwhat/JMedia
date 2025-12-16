package com.playdeca.jmedia.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playdeca.jmedia.data.model.Playlist
import com.playdeca.jmedia.data.model.Profile
import com.playdeca.jmedia.data.model.Song
import com.playdeca.jmedia.data.repository.JMusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentProfile: Profile? = null,
    val playlists: List<Playlist> = emptyList(),
    val songs: List<Song> = emptyList(),
    val currentSong: Song? = null,
    val isPlaying: Boolean = false
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: JMusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            repository.getCurrentProfile().fold(
                onSuccess = { profile ->
                    _uiState.value = _uiState.value.copy(currentProfile = profile)
                    profile?.let { loadLibraryData(it.id) }
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message
                    )
                }
            )
        }
    }

    private fun loadLibraryData(profileId: Int) {
        viewModelScope.launch {
            // Load playlists
            repository.getPlaylists(profileId).fold(
                onSuccess = { playlists ->
                    _uiState.value = _uiState.value.copy(playlists = playlists)
                    // Load songs from all playlists
                    val allSongs = playlists.flatMap { it.songs }
                    _uiState.value = _uiState.value.copy(
                        songs = allSongs,
                        isLoading = false
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message
                    )
                }
            )
        }
    }

    fun selectSong(songId: Int) {
        val profileId = _uiState.value.currentProfile?.id ?: return
        viewModelScope.launch {
            repository.selectSong(profileId, songId).fold(
                onSuccess = {
                    // Song selected successfully
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
            )
        }
    }

    fun togglePlayPause() {
        val profileId = _uiState.value.currentProfile?.id ?: return
        viewModelScope.launch {
            repository.togglePlayPause(profileId).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isPlaying = !_uiState.value.isPlaying
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}