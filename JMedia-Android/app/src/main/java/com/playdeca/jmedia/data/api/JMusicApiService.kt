package com.playdeca.jmedia.data.api

import com.playdeca.jmedia.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface JMusicApiService {
    
    // Profile Management
    @GET("/api/profiles")
    suspend fun getProfiles(): Response<ApiResponse<List<Profile>>>
    
    @GET("/api/profiles/current")
    suspend fun getCurrentProfile(): Response<ApiResponse<Profile>>
    
    @POST("/api/profiles/switch/{profileId}")
    suspend fun switchToProfile(@Path("profileId") profileId: Int): Response<ApiResponse<Unit>>
    
    // Library Management
    @GET("/api/music/playlists/{profileId}")
    suspend fun getPlaylists(@Path("profileId") profileId: Int): Response<ApiResponse<List<Playlist>>>
    
    @GET("/api/music/playlists/{id}")
    suspend fun getPlaylist(@Path("id") id: Int): Response<ApiResponse<Playlist>>
    
    // Playback Control
    @GET("/api/music/playback/current/{profileId}")
    suspend fun getCurrentPlaybackState(@Path("profileId") profileId: Int): Response<ApiResponse<PlaybackState>>
    
    @POST("/api/music/playback/toggle/{profileId}")
    suspend fun togglePlayPause(@Path("profileId") profileId: Int): Response<ApiResponse<Unit>>
    
    @POST("/api/music/playback/play/{profileId}")
    suspend fun play(@Path("profileId") profileId: Int): Response<ApiResponse<Unit>>
    
    @POST("/api/music/playback/pause/{profileId}")
    suspend fun pause(@Path("profileId") profileId: Int): Response<ApiResponse<Unit>>
    
    @POST("/api/music/playback/next/{profileId}")
    suspend fun next(@Path("profileId") profileId: Int): Response<ApiResponse<Unit>>
    
    @POST("/api/music/playback/previous/{profileId}")
    suspend fun previous(@Path("profileId") profileId: Int): Response<ApiResponse<Unit>>
    
    @POST("/api/music/playback/select/{profileId}/{songId}")
    suspend fun selectSong(
        @Path("profileId") profileId: Int,
        @Path("songId") songId: Int
    ): Response<ApiResponse<Unit>>
    
    @POST("/api/music/playback/volume/{profileId}/{level}")
    suspend fun setVolume(
        @Path("profileId") profileId: Int,
        @Path("level") level: Float
    ): Response<ApiResponse<Unit>>
    
    @POST("/api/music/playback/position/{profileId}/{seconds}")
    suspend fun setPosition(
        @Path("profileId") profileId: Int,
        @Path("seconds") seconds: Int
    ): Response<ApiResponse<Unit>>
    
    @POST("/api/music/playback/shuffle/{profileId}")
    suspend fun toggleShuffle(@Path("profileId") profileId: Int): Response<ApiResponse<Unit>>
    
    @POST("/api/music/playback/repeat/{profileId}")
    suspend fun toggleRepeat(@Path("profileId") profileId: Int): Response<ApiResponse<Unit>>
    
    // Queue Management
    @GET("/api/music/queue/{profileId}")
    suspend fun getQueue(@Path("profileId") profileId: Int): Response<ApiResponse<List<Song>>>
    
    @POST("/api/music/queue/add/{profileId}/{songId}")
    suspend fun addToQueue(
        @Path("profileId") profileId: Int,
        @Path("songId") songId: Int
    ): Response<ApiResponse<Unit>>
    
    @POST("/api/music/playback/queue-all/{profileId}/{playlistId}")
    suspend fun queueAllFromPlaylist(
        @Path("profileId") profileId: Int,
        @Path("playlistId") playlistId: Int
    ): Response<ApiResponse<Unit>>
    
    @POST("/api/music/queue/skip-to/{profileId}/{index}")
    suspend fun skipToQueueIndex(
        @Path("profileId") profileId: Int,
        @Path("index") index: Int
    ): Response<ApiResponse<Unit>>
    
    @POST("/api/music/queue/remove/{profileId}/{index}")
    suspend fun removeFromQueue(
        @Path("profileId") profileId: Int,
        @Path("index") index: Int
    ): Response<ApiResponse<Unit>>
    
    @POST("/api/music/queue/clear/{profileId}")
    suspend fun clearQueue(@Path("profileId") profileId: Int): Response<ApiResponse<Unit>>
    
    // Song Management
    @GET("/api/song/{id}/lyrics")
    suspend fun getSongLyrics(@Path("id") id: Int): Response<ApiResponse<String>>
    
    // History
    @GET("/api/music/history/{profileId}")
    suspend fun getPlaybackHistory(
        @Path("profileId") profileId: Int,
        @Query("page") page: Int = 0,
        @Query("limit") limit: Int = 50
    ): Response<ApiResponse<List<Song>>>
    
    // Settings
    @GET("/api/settings/{profileId}")
    suspend fun getSettings(@Path("profileId") profileId: Int): Response<ApiResponse<Settings>>
    
    @POST("/api/settings/{profileId}/scanLibrary")
    suspend fun scanLibrary(@Path("profileId") profileId: Int): Response<ApiResponse<Unit>>
    
    @POST("/api/settings/{profileId}/scanLibraryIncremental")
    suspend fun scanLibraryIncremental(@Path("profileId") profileId: Int): Response<ApiResponse<Unit>>
    
    // Video API
    @GET("/api/video/videos")
    suspend fun getVideos(@Query("mediaType") mediaType: String? = null): Response<ApiResponse<List<Video>>>
    
    @GET("/api/video/movies")
    suspend fun getMovies(
        @Query("page") page: Int = 0,
        @Query("limit") limit: Int = 50
    ): Response<ApiResponse<List<Video>>>
    
    @GET("/api/video/shows")
    suspend fun getShows(): Response<ApiResponse<List<String>>>
    
    @GET("/api/video/shows/{seriesTitle}/seasons")
    suspend fun getSeasons(@Path("seriesTitle") seriesTitle: String): Response<ApiResponse<List<Int>>>
    
    @GET("/api/video/shows/{seriesTitle}/seasons/{seasonNumber}/episodes")
    suspend fun getEpisodes(
        @Path("seriesTitle") seriesTitle: String,
        @Path("seasonNumber") seasonNumber: Int
    ): Response<ApiResponse<List<Video>>>
    
    @GET("/api/video/stream/{videoId}")
    suspend fun streamVideo(@Path("videoId") videoId: Int): Response<okhttp3.ResponseBody>
}