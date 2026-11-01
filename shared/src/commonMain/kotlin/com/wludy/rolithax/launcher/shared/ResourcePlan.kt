package com.wludy.rolithax.launcher.shared

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val MAX_RESOURCE_DEPENDENCIES = 50

data class ResourcePlanEntry(val project: ModrinthProject, val version: ModrinthVersion, val type: String)
data class ResourcePlan(val entries: List<ResourcePlanEntry>)

suspend fun ModrinthClient.planInstall(
    project: ModrinthProject,
    version: ModrinthVersion,
    type: String,
    gameVersion: String,
    loader: String,
): ResourcePlan {
    require(type in setOf("mod", "plugin") && gameVersion.isNotBlank() && loader.isNotBlank())
    val fullProject = project(project.id)
    if (!fullProject.id.equals(project.id, true)) throw DaemonException("资源项目标识不匹配")
    val selected = linkedMapOf(project.id.lowercase() to ResourcePlanEntry(fullProject, version, type))
    val queue = ArrayDeque<ResourcePlanEntry>()
    queue.addLast(selected.values.first())
    val conflicts = mutableListOf<Pair<String, String>>()
    val targetNames = mutableSetOf<String>()
    while (queue.isNotEmpty()) {
        if (selected.size > MAX_RESOURCE_DEPENDENCIES) throw DaemonException("依赖数量超过安全上限")
        val entry = queue.removeFirst()
        val projectId = entry.project.id
        if (projectId.isBlank() || !entry.version.projectId.equals(projectId, true)) throw DaemonException("资源版本与项目不匹配")
        val declaredTypes = entry.project.projectTypes.ifEmpty { listOfNotNull(entry.project.projectType) }
        if (entry.type !in declaredTypes) throw DaemonException("资源项目类型不匹配")
        if (entry.version.gameVersions.none { it.equals(gameVersion, true) } || entry.version.loaders.none { it.equals(loader, true) }) throw DaemonException("资源版本与服务器不兼容")
        if ("unsupported" in values(entry.project.serverSide) || "unsupported" in values(entry.version.serverSide) || "client_only" in values(entry.project.loaderFields?.environment ?: entry.project.environment)) throw DaemonException("资源不支持服务端")
        val file = entry.version.files.firstOrNull { it.primary } ?: entry.version.files.firstOrNull() ?: throw DaemonException("资源缺少安装文件")
        if (file.filename.isBlank() || file.filename in setOf(".", "..") || '/' in file.filename || '\\' in file.filename) throw DaemonException("资源文件名无效")
        if (!targetNames.add(entry.type + "/" + file.filename.lowercase())) throw DaemonException("多个资源的目标文件名重复")
        if (!file.hashes.any { (algorithm, hash) ->
            val length = when (algorithm.lowercase()) { "sha512" -> 128; "sha256" -> 64; "sha1" -> 40; else -> 0 }
            length > 0 && hash.length == length && hash.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
        }) throw DaemonException("资源缺少完整摘要")
        val dependencies = entry.version.dependencies ?: throw DaemonException("缺少依赖信息，无法安全安装")
        for (dependency in dependencies) {
            if (dependency.type != "required" && dependency.type != "incompatible") continue
            val dependencyProjectId = dependency.projectId?.takeIf { it.isNotBlank() }
                ?: dependency.versionId?.takeIf { it.isNotBlank() }?.let { version(it).projectId }
                ?: throw DaemonException("依赖项缺少项目标识")
            val key = dependencyProjectId.lowercase()
            if (dependency.type == "incompatible") {
                conflicts += projectId.lowercase() to key
                continue
            }
            val existing = selected[key]
            if (existing != null) {
                if (!dependency.versionId.isNullOrBlank() && existing.version.id != dependency.versionId) throw DaemonException("必需依赖版本冲突")
                continue
            }
            val dependencyProject = project(dependencyProjectId)
            if (!dependencyProject.id.equals(dependencyProjectId, true)) throw DaemonException("依赖项目标识不匹配")
            val candidates = versions(dependencyProjectId, gameVersion, loader)
            val dependencyVersion = (if (!dependency.versionId.isNullOrBlank()) candidates.firstOrNull { it.id == dependency.versionId } else candidates.firstOrNull())
                ?: throw DaemonException("找不到兼容的必需依赖版本")
            val dependencyType = dependencyProject.projectTypes.firstOrNull { it in setOf("mod", "plugin") } ?: dependencyProject.projectType ?: throw DaemonException("必需依赖缺少资源类型")
            if (dependencyType !in setOf("mod", "plugin")) throw DaemonException("必需依赖的资源类型不受支持")
            val next = ResourcePlanEntry(dependencyProject, dependencyVersion, dependencyType)
            selected[key] = next
            queue.addLast(next)
        }
    }
    if (conflicts.any { (source, target) -> source in selected && target in selected }) throw DaemonException("所选资源之间存在不兼容的依赖")
    return ResourcePlan(selected.values.toList())
}

private fun values(value: JsonElement?): List<String> = when (value) {
    is JsonPrimitive -> listOfNotNull(value.contentOrNull)
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    else -> emptyList()
}
