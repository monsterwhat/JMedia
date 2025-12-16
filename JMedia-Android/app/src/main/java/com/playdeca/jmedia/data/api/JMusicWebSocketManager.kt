package com.playdeca.jmedia.data.api

import com.playdeca.jmedia.data.model.PlaybackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

@InternalSerializationApi
@Serializable
data class WebSocketMessage(
    val type: String,
    val payload: String? = null
)

@InternalSerializationApi
@Serializable
data class SetProfilePayload(
    val profileId: Int
)

@InternalSerializationApi
@Serializable
data class SeekPayload(
    val value: Double
)

@InternalSerializationApi
@Serializable
data class VolumePayload(
    val value: Float
)

class JMusicWebSocketManager(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String
) {
    private var webSocket: WebSocket? = null
    private var currentProfileId: Int? = null
    
    private val _playbackStateFlow = MutableSharedFlow<PlaybackState>()
    val playbackStateFlow: Flow<PlaybackState> = _playbackStateFlow
    
    private val _connectionStateFlow = MutableSharedFlow<Boolean>()
    val connectionStateFlow: Flow<Boolean> = _connectionStateFlow
    
    private val json = Json { ignoreUnknownKeys = true }
    
    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _connectionStateFlow.tryEmit(true)
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                when {
                    text.contains("\"state\"") -> {
                        val state = json.decodeFromString<PlaybackState>(text)
                        _playbackStateFlow.tryEmit(state)
                    }
                }
            } catch (e: Exception) {
                // Handle parsing errors
            }
        }
        
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _connectionStateFlow.tryEmit(false)
        }
    }
    
    fun connect(profileId: Int) {
        disconnect()
        currentProfileId = profileId
        
        val request = Request.Builder()
            .url("$baseUrl/api/music/ws/$profileId")
            .build()
        
        webSocket = okHttpClient.newWebSocket(request, webSocketListener)
    }
    
    fun disconnect() {
        webSocket?.close(1000, "Disconnecting")
        webSocket = null
        currentProfileId = null
    }
    
    @OptIn(InternalSerializationApi::class)
    fun sendSeek(position: Double) {
        val payload = Json.encodeToString(SeekPayload.serializer(), SeekPayload(position))
        val message = WebSocketMessage(type = "seek", payload = payload)
        sendMessage(message)
    }
    
    @OptIn(InternalSerializationApi::class)
    fun sendVolume(volume: Float) {
        val payload = Json.encodeToString(VolumePayload.serializer(), VolumePayload(volume))
        val message = WebSocketMessage(type = "volume", payload = payload)
        sendMessage(message)
    }
    
    @OptIn(InternalSerializationApi::class)
    fun sendNext() {
        val message = WebSocketMessage(type = "next")
        sendMessage(message)
    }
    
    @OptIn(InternalSerializationApi::class)
    fun sendPrevious() {
        val message = WebSocketMessage(type = "previous")
        sendMessage(message)
    }
    
    @OptIn(InternalSerializationApi::class)
    fun sendSetProfile(profileId: Int) {
        val payload = Json.encodeToString(SetProfilePayload.serializer(), SetProfilePayload(profileId))
        val message = WebSocketMessage(type = "setProfile", payload = payload)
        sendMessage(message)
    }
    
    @OptIn(InternalSerializationApi::class)
    private fun sendMessage(message: WebSocketMessage) {
        webSocket?.send(json.encodeToString(WebSocketMessage.serializer(), message))
    }
}