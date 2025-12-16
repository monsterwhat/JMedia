package com.playdeca.jmedia.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Playlist(
    val id: Int,
    val name: String,
    val description: String? = null,
    val isGlobal: Boolean = false,
    val songs: List<Song> = emptyList(),
    val profile: Profile? = null
) {
    val songCount: Int
        get() = songs.size
}