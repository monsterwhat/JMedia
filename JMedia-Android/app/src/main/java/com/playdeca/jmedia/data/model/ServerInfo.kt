package com.playdeca.jmedia.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ServerInfo(
    val id: String = "",
    val name: String = "",
    val version: String = "1.0",
    val lastSeen: String = ""
)

@Serializable
data class ServerConnection(
    val serverInfo: ServerInfo,
    val url: String,
    val port: Int,
    val lastConnected: String = "",
    val currentProfile: Profile? = null
) {
    val fullUrl: String
        get() = "http://$url:$port"
    
    val displayName: String
        get() = "${serverInfo.name} ($url:$port)"
}