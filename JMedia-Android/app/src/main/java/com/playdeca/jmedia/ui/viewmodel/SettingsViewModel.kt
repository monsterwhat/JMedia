package com.playdeca.jmedia.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playdeca.jmedia.data.model.Profile
import com.playdeca.jmedia.data.model.Settings
import com.playdeca.jmedia.data.repository.JMusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentProfile: Profile? = null,
    val profiles: List<Profile> = emptyList(),
    val settings: Settings? = null,
    val serverUrl: String = "http://localhost:8080"
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: JMusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message
                    )
                }
            )
            
            // Load all profiles
            repository.getProfiles().fold(
                onSuccess = { profiles ->
                    _uiState.value = _uiState.value.copy(profiles = profiles)
                },
                onFailure = { /* Handle error */ }
            )
            
            // Load settings if we have a profile
            _uiState.value.currentProfile?.let { profile ->
                loadSettings(profile.id)
            }
            
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    private fun loadSettings(profileId: Int) {
        viewModelScope.launch {
            repository.getSettings(profileId).fold(
                onSuccess = { settings ->
                    _uiState.value = _uiState.value.copy(settings = settings)
                },
                onFailure = { /* Handle error */ }
            )
        }
    }

    fun switchToProfile(profileId: Int) {
        viewModelScope.launch {
            repository.switchToProfile(profileId).fold(
                onSuccess = {
                    // Reload data after switching
                    loadData()
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
            )
        }
    }

    fun scanLibrary() {
        val profileId = _uiState.value.currentProfile?.id ?: return
        viewModelScope.launch {
            repository.scanLibrary(profileId).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(error = "Library scan started")
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
            )
        }
    }

    fun scanLibraryIncremental() {
        val profileId = _uiState.value.currentProfile?.id ?: return
        viewModelScope.launch {
            repository.scanLibraryIncremental(profileId).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(error = "Incremental library scan started")
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