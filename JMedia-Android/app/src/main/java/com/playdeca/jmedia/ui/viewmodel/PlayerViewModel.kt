package com.playdeca.jmedia.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playdeca.jmedia.data.model.PlaybackState
import com.playdeca.jmedia.data.model.Profile
import com.playdeca.jmedia.data.model.Song
import com.playdeca.jmedia.data.repository.JMusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentProfile: Profile? = null,
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
    val volume: Float = 0.8f,
    val isShuffled: Boolean = false,
    val isRepeating: Boolean = false
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: JMusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            repository.getCurrentProfile().fold(
                onSuccess = { profile ->
                    _uiState.value = _uiState.value.copy(currentProfile = profile)
                    profile?.let { loadPlaybackData(it.id) }
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

    private fun loadPlaybackData(profileId: Int) {
        viewModelScope.launch {
            repository.getCurrentPlaybackState(profileId).fold(
                onSuccess = { playbackState ->
                    _uiState.value = _uiState.value.copy(
                        isPlaying = playbackState.isPlaying,
                        position = playbackState.position.toLong(),
                        volume = playbackState.volume,
                        isShuffled = playbackState.shuffle,
                        isRepeating = playbackState.repeat,
                        isLoading = false
                    )
                    
                    // Load current song if available
                    playbackState.currentSongId?.let { songId ->
                        // We would need to implement a method to get song by ID
                        // For now, we'll skip this part
                    }
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

    fun next() {
        val profileId = _uiState.value.currentProfile?.id ?: return
        viewModelScope.launch {
            repository.next(profileId).fold(
                onSuccess = { /* Success */ },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
            )
        }
    }

    fun previous() {
        val profileId = _uiState.value.currentProfile?.id ?: return
        viewModelScope.launch {
            repository.previous(profileId).fold(
                onSuccess = { /* Success */ },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
            )
        }
    }

    fun seekTo(position: Long) {
        val profileId = _uiState.value.currentProfile?.id ?: return
        viewModelScope.launch {
            repository.setPosition(profileId, (position / 1000).toInt()).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(position = position)
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
            )
        }
    }

    fun setVolume(volume: Float) {
        val profileId = _uiState.value.currentProfile?.id ?: return
        viewModelScope.launch {
            repository.setVolume(profileId, volume).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(volume = volume)
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
            )
        }
    }

    fun toggleShuffle() {
        val profileId = _uiState.value.currentProfile?.id ?: return
        viewModelScope.launch {
            repository.toggleShuffle(profileId).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isShuffled = !_uiState.value.isShuffled
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
            )
        }
    }

    fun toggleRepeat() {
        val profileId = _uiState.value.currentProfile?.id ?: return
        viewModelScope.launch {
            repository.toggleRepeat(profileId).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isRepeating = !_uiState.value.isRepeating
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