package com.playdeca.jmedia.data.repository

import com.playdeca.jmedia.data.api.JMusicApiService
import com.playdeca.jmedia.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JMusicRepository @Inject constructor(
    private val apiService: JMusicApiService
) {
    // Profile Management
    suspend fun getProfiles(): Result<List<Profile>> = try {
        val response = apiService.getProfiles()
        if (response.isSuccessful) {
            response.body()?.data?.let { profiles ->
                Result.success(profiles)
            } ?: Result.failure(Exception("No profiles found"))
        } else {
            Result.failure(Exception("Failed to fetch profiles: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
    
    suspend fun getCurrentProfile(): Result<Profile?> = try {
        val response = apiService.getCurrentProfile()
        if (response.isSuccessful) {
            Result.success(response.body()?.data)
        } else {
            Result.failure(Exception("Failed to fetch current profile: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
    
    suspend fun switchToProfile(profileId: Int): Result<Unit> = try {
        val response = apiService.switchToProfile(profileId)
        if (response.isSuccessful) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Failed to switch profile: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
    
    // Library Management
    suspend fun getPlaylists(profileId: Int): Result<List<Playlist>> = try {
        val response = apiService.getPlaylists(profileId)
        if (response.isSuccessful) {
            response.body()?.data?.let { playlists ->
                Result.success(playlists)
            } ?: Result.failure(Exception("No playlists found"))
        } else {
            Result.failure(Exception("Failed to fetch playlists: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
    
    suspend fun getPlaylist(id: Int): Result<Playlist> = try {
        val response = apiService.getPlaylist(id)
        if (response.isSuccessful) {
            response.body()?.data?.let { playlist ->
                Result.success(playlist)
            } ?: Result.failure(Exception("Playlist not found"))
        } else {
            Result.failure(Exception("Failed to fetch playlist: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
    
    // Playback Control
    suspend fun getCurrentPlaybackState(profileId: Int): Result<PlaybackState> = try {
        val response = apiService.getCurrentPlaybackState(profileId)
        if (response.isSuccessful) {
            response.body()?.data?.let { state ->
                Result.success(state)
            } ?: Result.failure(Exception("No playback state found"))
        } else {
            Result.failure(Exception("Failed to fetch playback state: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
    
    suspend fun togglePlayPause(profileId: Int): Result<Unit> = safeApiCall {
        apiService.togglePlayPause(profileId)
    }
    
    suspend fun play(profileId: Int): Result<Unit> = safeApiCall {
        apiService.play(profileId)
    }
    
    suspend fun pause(profileId: Int): Result<Unit> = safeApiCall {
        apiService.pause(profileId)
    }
    
    suspend fun next(profileId: Int): Result<Unit> = safeApiCall {
        apiService.next(profileId)
    }
    
    suspend fun previous(profileId: Int): Result<Unit> = safeApiCall {
        apiService.previous(profileId)
    }
    
    suspend fun selectSong(profileId: Int, songId: Int): Result<Unit> = safeApiCall {
        apiService.selectSong(profileId, songId)
    }
    
    suspend fun setVolume(profileId: Int, volume: Float): Result<Unit> = safeApiCall {
        apiService.setVolume(profileId, volume)
    }
    
    suspend fun setPosition(profileId: Int, seconds: Int): Result<Unit> = safeApiCall {
        apiService.setPosition(profileId, seconds)
    }
    
    suspend fun toggleShuffle(profileId: Int): Result<Unit> = safeApiCall {
        apiService.toggleShuffle(profileId)
    }
    
    suspend fun toggleRepeat(profileId: Int): Result<Unit> = safeApiCall {
        apiService.toggleRepeat(profileId)
    }
    
    // Queue Management
    suspend fun getQueue(profileId: Int): Result<List<Song>> = try {
        val response = apiService.getQueue(profileId)
        if (response.isSuccessful) {
            Result.success(response.body()?.data ?: emptyList())
        } else {
            Result.failure(Exception("Failed to fetch queue: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
    
    suspend fun addToQueue(profileId: Int, songId: Int): Result<Unit> = safeApiCall {
        apiService.addToQueue(profileId, songId)
    }
    
    suspend fun queueAllFromPlaylist(profileId: Int, playlistId: Int): Result<Unit> = safeApiCall {
        apiService.queueAllFromPlaylist(profileId, playlistId)
    }
    
    suspend fun skipToQueueIndex(profileId: Int, index: Int): Result<Unit> = safeApiCall {
        apiService.skipToQueueIndex(profileId, index)
    }
    
    suspend fun removeFromQueue(profileId: Int, index: Int): Result<Unit> = safeApiCall {
        apiService.removeFromQueue(profileId, index)
    }
    
    suspend fun clearQueue(profileId: Int): Result<Unit> = safeApiCall {
        apiService.clearQueue(profileId)
    }
    
    // Song Management
    suspend fun getSongLyrics(songId: Int): Result<String> = try {
        val response = apiService.getSongLyrics(songId)
        if (response.isSuccessful) {
            Result.success(response.body()?.data ?: "")
        } else {
            Result.failure(Exception("Failed to fetch lyrics: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
    
    // History
    suspend fun getPlaybackHistory(profileId: Int, page: Int = 0, limit: Int = 50): Result<List<Song>> = try {
        val response = apiService.getPlaybackHistory(profileId, page, limit)
        if (response.isSuccessful) {
            Result.success(response.body()?.data ?: emptyList())
        } else {
            Result.failure(Exception("Failed to fetch history: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
    
    // Settings
    suspend fun getSettings(profileId: Int): Result<Settings> = try {
        val response = apiService.getSettings(profileId)
        if (response.isSuccessful) {
            response.body()?.data?.let { settings ->
                Result.success(settings)
            } ?: Result.failure(Exception("No settings found"))
        } else {
            Result.failure(Exception("Failed to fetch settings: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
    
    suspend fun scanLibrary(profileId: Int): Result<Unit> = safeApiCall {
        apiService.scanLibrary(profileId)
    }
    
    suspend fun scanLibraryIncremental(profileId: Int): Result<Unit> = safeApiCall {
        apiService.scanLibraryIncremental(profileId)
    }
    
    // Video
    suspend fun getVideos(mediaType: String? = null): Result<List<Video>> = try {
        val response = apiService.getVideos(mediaType)
        if (response.isSuccessful) {
            Result.success(response.body()?.data ?: emptyList())
        } else {
            Result.failure(Exception("Failed to fetch videos: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
    
    private suspend fun safeApiCall(apiCall: suspend () -> retrofit2.Response<ApiResponse<Unit>>): Result<Unit> = try {
        val response = apiCall()
        if (response.isSuccessful) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("API call failed: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}