package com.wludy.rolithax.launcher.shared

import kotlinx.serialization.Serializable

@Serializable
data class DaemonInstanceExtra(val onlinePlayers: Int = 0)

@Serializable
data class DaemonInstance(
    val id: Long,
    val name: String? = null,
    val basePath: String? = null,
    val java: String? = null,
    val core: String? = null,
    val icon: String? = null,
    val status: Int = 0,
    val statusText: String? = null,
    val expireTime: String? = null,
    val extra: DaemonInstanceExtra? = null,
)

@Serializable
data class DaemonInstanceInfo(
    val id: Long,
    val name: String? = null,
    val status: Int = 0,
    val statusText: String? = null,
    val uptime: String? = null,
    val onlinePlayers: Int = 0,
    val java: String? = null,
    val core: String? = null,
)

@Serializable
data class DaemonFile(
    val name: String = "",
    val type: String = "file",
    val size: Long = 0,
    val lastModified: String? = null,
    val permission: String = "",
)

@Serializable
internal data class ApiResponse<T>(val code: Int = 0, val message: String? = null, val data: T? = null)

@Serializable
internal data class ActionRequest(val id: Long, val action: String)

@Serializable
internal data class SaveFileRequest(val path: String, val content: String)
