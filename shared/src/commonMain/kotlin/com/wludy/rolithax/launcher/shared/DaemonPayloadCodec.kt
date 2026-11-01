package com.wludy.rolithax.launcher.shared

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

fun normalizeInstanceList(payload: String): String = runCatching {
    val response = json.decodeFromString<ApiResponse<List<DaemonInstance>>>(payload)
    require(response.code == 200)
    json.encodeToString(requireNotNull(response.data))
}.getOrDefault("")

fun normalizeInstanceInfo(payload: String): String = runCatching {
    val response = json.decodeFromString<ApiResponse<DaemonInstanceInfo>>(payload)
    require(response.code == 200)
    json.encodeToString(requireNotNull(response.data))
}.getOrDefault("")

fun normalizeFileList(payload: String): String = runCatching {
    val response = json.decodeFromString<ApiResponse<List<DaemonFile>>>(payload)
    require(response.code == 200)
    json.encodeToString(requireNotNull(response.data))
}.getOrDefault("")

fun normalizeFileContent(payload: String): String = runCatching {
    val response = json.decodeFromString<ApiResponse<String>>(payload)
    require(response.code == 200)
    json.encodeToString(requireNotNull(response.data))
}.getOrDefault("")
