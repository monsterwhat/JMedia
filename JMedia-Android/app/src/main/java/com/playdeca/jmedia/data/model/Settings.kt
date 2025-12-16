package com.playdeca.jmedia.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val libraryPath: String? = null,
    val videoLibraryPath: String? = null,
    val outputFormat: String = "mp3",
    val downloadThreads: Int = 4,
    val searchThreads: Int = 4,
    val runAsService: Boolean = false
)