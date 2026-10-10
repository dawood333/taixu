package top.wkbin.taixu.harness

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.taixu.core.database.BuildScriptEntity
import top.wkbin.taixu.core.database.BuildScriptRepository

/** Controlled Harness API for reusable workshop scripts. */
class BuildScriptToolExecutor(
    private val repository: BuildScriptRepository,
) {
    suspend fun execute(args: JsonObject, workspace: String): Pair<Boolean, String> {
        val action = args.string("action").trim().lowercase()
        return when (action) {
            "list" -> {
                val scripts = repository.listScripts()
                true to if (scripts.isEmpty()) "No build scripts yet" else scripts.joinToString("\n") {
                    "${it.id}\t[${it.projectType}]\t${it.name}\t${it.description.ifBlank { "No description" }}${if (it.isBuiltin) "\t[builtin]" else ""}"
                }
            }
            "get" -> {
                val id = args.string("id")
                if (id.isBlank()) return false to "Missing required parameter: id"
                val script = repository.findScript(id) ?: return false to "Build script not found: $id"
                true to "id: ${script.id}\nname: ${script.name}\nproject_type: ${script.projectType}\ndescription: ${script.description}\n--- script ---\n${script.content}"
            }
            "create", "update" -> {
                val id = args.string("id")
                if (action == "update" && id.isBlank()) return false to "Missing required parameter: id"
                val old = if (action == "update") repository.findScript(id) ?: return false to "Build script not found: $id" else null
                val name = args.string("name").ifBlank { old?.name.orEmpty() }
                val type = args.string("project_type").ifBlank { old?.projectType.orEmpty() }.trim().uppercase()
                val content = args.string("content").ifBlank { old?.content.orEmpty() }
                if (name.isBlank()) return false to "name cannot be empty"
                if (type !in setOf("ANDROID", "FLUTTER")) return false to "project_type must be android or flutter"
                if (content.isBlank() || content.length > 200_000) return false to "content cannot be empty or exceed 200 KB"
                if (!content.lineSequence().firstOrNull().orEmpty().contains("sh")) return false to "The first line must declare the shell (for example, #!/bin/sh)"
                val now = System.currentTimeMillis()
                val saved = BuildScriptEntity(
                    id = old?.id ?: UUID.randomUUID().toString(),
                    name = name.trim(),
                    description = args.string("description").ifBlank { old?.description.orEmpty() }.trim(),
                    projectType = type,
                    content = content.removePrefix("\uFEFF").replace("\r\n", "\n"),
                    isBuiltin = old?.isBuiltin ?: false,
                    createdAt = old?.createdAt ?: now,
                    updatedAt = now,
                )
                repository.upsertScript(saved)
                true to "Build script ${if (old == null) "created" else "updated"}: ${saved.name} (${saved.id})"
            }
            "delete" -> {
                val id = args.string("id")
                if (id.isBlank()) return false to "Missing required parameter: id"
                if (repository.deleteScript(id)) true to "Deleted build script and its project mount" else false to "Built-in scripts cannot be deleted, or the script does not exist"
            }
            "bind" -> {
                val project = resolveProject(args, workspace)
                if (project.isBlank()) return false to "project cannot be empty, and the current session has no workspace"
                val id = args.string("id")
                if (id.isBlank()) return false to "Missing required parameter: id"
                runCatching {
                    repository.bind(project, id)
                }.fold(
                    onSuccess = { true to "Mounted project $project to build script $id" },
                    onFailure = { false to (it.message ?: "挂载失败") },
                )
            }
            "unbind" -> {
                val project = resolveProject(args, workspace)
                if (project.isBlank()) return false to "project cannot be empty, and the current session has no workspace"
                repository.unbind(project)
                true to "项目 $project 已恢复标准构建流程"
            }
            else -> false to "未知 action：$action"
        }
    }

    private fun resolveProject(args: JsonObject, workspace: String): String =
        args.string("project").ifBlank { workspace.trim().trimEnd('/').substringAfterLast('/') }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()
}
