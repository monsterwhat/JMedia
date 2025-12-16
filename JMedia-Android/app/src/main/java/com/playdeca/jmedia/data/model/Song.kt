package com.playdeca.jmedia.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Song(
    val id: Int,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Int,
    val path: String,
    val lyrics: String? = null,
    val artwork: String? = null
) {
    val durationFormatted: String
        get() {
            val minutes = duration / 60
            val seconds = duration % 60
            return String.format("%d:%02d", minutes, seconds)
        }
    
    val streamUrl: String
        get() = "/api/music/stream/$id"
}