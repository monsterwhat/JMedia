package com.playdeca.jmedia.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playdeca.jmedia.data.model.PlaybackState
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

data class HomeUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentProfile: Profile? = null,
    val playlists: List<Playlist> = emptyList(),
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val playbackState: PlaybackState? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: JMusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            // Load current profile
            repository.getCurrentProfile().fold(
                onSuccess = { profile ->
                    _uiState.value = _uiState.value.copy(currentProfile = profile)
                    profile?.let { loadProfileData(it.id) }
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

    private fun loadProfileData(profileId: Int) {
        viewModelScope.launch {
            // Load playlists
            repository.getPlaylists(profileId).fold(
                onSuccess = { playlists ->
                    _uiState.value = _uiState.value.copy(
                        playlists = playlists,
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
            
            // Load playback state
            repository.getCurrentPlaybackState(profileId).fold(
                onSuccess = { playbackState ->
                    _uiState.value = _uiState.value.copy(
                        playbackState = playbackState,
                        isPlaying = playbackState.isPlaying
                    )
                },
                onFailure = { /* Handle error */ }
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