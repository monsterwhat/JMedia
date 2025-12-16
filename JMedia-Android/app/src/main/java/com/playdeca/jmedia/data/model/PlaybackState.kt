package com.playdeca.jmedia.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackState(
    val currentSongId: Int? = null,
    val isPlaying: Boolean = false,
    val position: Double = 0.0,
    val volume: Float = 0.8f,
    val shuffle: Boolean = false,
    val repeat: Boolean = false
)