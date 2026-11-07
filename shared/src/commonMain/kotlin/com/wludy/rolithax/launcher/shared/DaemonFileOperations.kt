package com.wludy.rolithax.launcher.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
private data class DirectoryRequest(val path: String, val name: String)

@Serializable
private data class RenameRequest(val oldPath: String, val newPath: String)

@Serializable
private data class DeleteRequest(val paths: List<String>)

suspend fun DaemonClient.createDirectory(instanceId: Long, path: String, name: String): String? {
    require(instanceId > 0 && validRemotePath(path, allowEmpty = true) && validRemoteLeaf(name))
    return postResponse("api/files/instance/$instanceId/directory", json.encodeToString(DirectoryRequest(path, name))).message
}

suspend fun DaemonClient.renameRemoteFile(instanceId: Long, oldPath: String, newPath: String): String? {
    require(instanceId > 0 && validRemotePath(oldPath) && validRemotePath(newPath))
    return postResponse("api/files/instance/$instanceId/rename", json.encodeToString(RenameRequest(oldPath, newPath))).message
}

suspend fun DaemonClient.deleteRemoteFile(instanceId: Long, path: String): String? =
    deleteRemoteFiles(instanceId, listOf(path)).message

internal suspend fun DaemonClient.deleteRemoteFiles(instanceId: Long, paths: List<String>): DaemonResponse {
    require(instanceId > 0 && paths.isNotEmpty() && paths.all { validRemotePath(it) }) { "文件路径无效" }
    return postResponse("api/files/instance/$instanceId/delete", json.encodeToString(DeleteRequest(paths)))
}
