package com.playdeca.jmedia.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Video(
    val id: Int,
    val title: String,
    val mediaType: String,
    val path: String,
    val duration: Int,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val seriesTitle: String? = null
) {
    val durationFormatted: String
        get() {
            val minutes = duration / 60
            val seconds = duration % 60
            return String.format("%d:%02d", minutes, seconds)
        }
}