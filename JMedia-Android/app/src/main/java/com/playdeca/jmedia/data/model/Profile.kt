package com.playdeca.jmedia.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: Int,
    val name: String,
    val isMainProfile: Boolean = false
)