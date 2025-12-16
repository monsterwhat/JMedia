package com.playdeca.jmedia.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playdeca.jmedia.data.model.Profile
import com.playdeca.jmedia.data.model.Song
import com.playdeca.jmedia.data.repository.JMusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QueueUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentProfile: Profile? = null,
    val queue: List<Song> = emptyList(),
    val currentSongId: Int? = null
)

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val repository: JMusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(QueueUiState())
    val uiState: StateFlow<QueueUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            repository.getCurrentProfile().fold(
                onSuccess = { profile ->
                    _uiState.value = _uiState.value.copy(currentProfile = profile)
                    profile?.let { loadQueueData(it.id) }
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

    private fun loadQueueData(profileId: Int) {
        viewModelScope.launch {
            repository.getQueue(profileId).fold(
                onSuccess = { queue ->
                    _uiState.value = _uiState.value.copy(
                        queue = queue,
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

    fun playFromQueue(index: Int) {
        val profileId = _uiState.value.currentProfile?.id ?: return
        viewModelScope.launch {
            repository.skipToQueueIndex(profileId, index).fold(
                onSuccess = { /* Success */ },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
            )
        }
    }

    fun removeFromQueue(index: Int) {
        val profileId = _uiState.value.currentProfile?.id ?: return
        viewModelScope.launch {
            repository.removeFromQueue(profileId, index).fold(
                onSuccess = {
                    // Reload queue to get updated list
                    loadQueueData(profileId)
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
            )
        }
    }

    fun clearQueue() {
        val profileId = _uiState.value.currentProfile?.id ?: return
        viewModelScope.launch {
            repository.clearQueue(profileId).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(queue = emptyList())
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