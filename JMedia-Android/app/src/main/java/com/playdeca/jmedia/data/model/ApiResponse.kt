package com.playdeca.jmedia.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val data: T? = null,
    val error: String? = null
)