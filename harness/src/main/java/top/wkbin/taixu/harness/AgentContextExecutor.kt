package top.wkbin.taixu.harness

import top.wkbin.taixu.core.database.AgentContextRepository
import top.wkbin.taixu.core.database.AgentMemoryEntity
import top.wkbin.taixu.core.database.AgentPlanEntity
import top.wkbin.taixu.core.database.AgentScratchpadEntity
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 负责执行 agent-context 核心系统能力工具：
 * - memory: 长期语义与事实记忆
 * - plan: 多步骤任务执行计划管理
 * - scratchpad: 任务局部工作草稿与排查便签
 */
class AgentContextExecutor(
    private val agentContextDao: AgentContextRepository,
    private val json: Json,
) {
    suspend fun executeMemory(args: JsonObject, sessionId: String, workspace: String): Pair<Boolean, String> {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        return when (action) {
            "save" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "memory save requires a key parameter"
                val value = args["value"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "memory save requires a value parameter"
                if (key.length > MAX_MEMORY_KEY_CHARS) return false to "memory key cannot exceed $MAX_MEMORY_KEY_CHARS characters"
                if (value.length > MAX_MEMORY_VALUE_CHARS) return false to "memory value cannot exceed $MAX_MEMORY_VALUE_CHARS characters"
                val kind = args["kind"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "fact"
                val scope = args["scope"]?.jsonPrimitive?.contentOrNull?.lowercase()
                    ?: if (workspace.isNotBlank()) "project" else "global"
                val ownerId = memoryOwner(scope, sessionId, workspace)
                    ?: return false to "scope supports only global/project/session; project/session also require the corresponding context"
                val subjectKey = args["subjectKey"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotBlank() } ?: key
                val volatility = args["volatility"]?.jsonPrimitive?.contentOrNull?.lowercase()
                    ?.takeIf { it in setOf("reference", "project", "user") } ?: "reference"
                val pinned = (args["pinned"]?.jsonPrimitive?.contentOrNull?.lowercase()) == "true"
                val expiresAt = args["expiresAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val now = System.currentTimeMillis()

                // 同主题冲突去重：同 (scope, ownerId, subjectKey) 只有一个活动修订。
                val existing = agentContextDao.getMemoryBySubjectKey(subjectKey, scope, ownerId)
                    ?: agentContextDao.getMemoryByKey(key, scope, ownerId)
                if (existing == null && agentContextDao.countMemories(scope, ownerId) >= MAX_MEMORIES_PER_OWNER) {
                    return false to "[$scope] memory limit ($MAX_MEMORIES_PER_OWNER) reached. Delete old memories before saving more."
                }
                if (pinned) {
                    val pinnedBudget = MAX_PINNED_CHARS - agentContextDao.getPinnedMemories(projectOwner(workspace), sessionId)
                        .filter { it.id != existing?.id }.sumOf { it.value.length }
                    if (value.length > pinnedBudget) {
                        return false to "Total pinned memory is near the character limit (~$MAX_PINNED_CHARS). Shorten it or unpin some entries"
                    }
                }
                val id = existing?.id ?: UUID.randomUUID().toString()
                val valueChanged = existing?.value != value
                agentContextDao.saveMemory(
                    AgentMemoryEntity(
                        id = id,
                        scope = scope,
                        ownerId = ownerId,
                        kind = kind,
                        key = key,
                        value = value,
                        subjectKey = subjectKey,
                        revision = (existing?.revision ?: 0) + if (valueChanged) 1 else 0,
                        pinned = pinned,
                        expiresAt = expiresAt,
                        lastVerifiedAt = existing?.lastVerifiedAt ?: 0,
                        volatility = volatility,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                    )
                )
                val conflictNote = if (existing != null && subjectKey != key && existing.key != subjectKey) {
                    " Deduplicated same-topic entry ($subjectKey) and advanced to revision=${existing.revision + if (valueChanged) 1 else 0}"
                } else ""
                val savedMessage = if (existing != null) {
                    val revNote = if (valueChanged) {
                        ", advanced to revision=${existing.revision + 1}"
                    } else {
                        "(content unchanged, revision=${existing.revision})"
                    }
                    "Updated existing long-term memory [$revNote; previous value overwritten][$scope/$kind] $key = $value$conflictNote"
                } else {
                    "Stored long-term memory successfully [$scope/$kind] $key = $value"
                }
                true to savedMessage
            }
            "verify" -> {
                // 新鲜度续期：模型确认记忆仍有效时刷新 lastVerifiedAt。
                val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim()
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                val target = when {
                    id != null -> agentContextDao.getMemoryById(id)
                    key != null -> {
                        val scope = args["scope"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "global"
                        val ownerId = memoryOwner(scope, sessionId, workspace)
                            ?: return false to "scope supports only global/project/session"
                        agentContextDao.getMemoryByKey(key, scope, ownerId)
                    }
                    else -> return false to "memory verify requires an id or key"
                } ?: return false to "Memory to verify not found"
                agentContextDao.touchMemory(target.id, System.currentTimeMillis())
                true to "Refreshed freshness for memory ${target.key} (extended lastVerifiedAt)"
            }
            "query", "search" -> {
                val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "memory query requires a query or key"
                val includeExpired = (args["include_expired"]?.jsonPrimitive?.contentOrNull?.lowercase()) == "true"
                val results = agentContextDao.searchMemories(
                    query = query.take(MAX_MEMORY_QUERY_CHARS),
                    projectOwnerId = projectOwner(workspace),
                    sessionId = sessionId,
                ).filter { includeExpired || isFresh(it, System.currentTimeMillis()) }
                if (results.isEmpty()) {
                    true to "No memories found for '$query'"
                } else {
                    val formatted = results.joinToString("\n") { it.render(lineBreak = true) }
                    true to "Memories found:\n$formatted"
                }
            }
            "list" -> {
                val includeExpired = (args["include_expired"]?.jsonPrimitive?.contentOrNull?.lowercase()) == "true"
                val results = agentContextDao.getFreshMemories(
                    projectOwnerId = projectOwner(workspace),
                    sessionId = sessionId,
                    pinned = false,
                    now = System.currentTimeMillis(),
                    limit = MAX_MEMORY_LIST,
                ).plus(
                    if (includeExpired) agentContextDao.getMemoriesForContext(projectOwner(workspace), sessionId)
                        .filter { !isFresh(it, System.currentTimeMillis()) }
                    else emptyList(),
                ).distinctBy { it.id }
                if (results.isEmpty()) {
                    true to "No long-term memories yet"
                } else {
                    val formatted = results.joinToString("\n") { it.render() }
                    true to "Saved memories:\n$formatted"
                }
            }
            "delete" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim()
                if (id != null) {
                    val memory = agentContextDao.getMemoryById(id)
                        ?: return false to "Memory not found: id=$id"
                    val visible = when (memory.scope) {
                        "global" -> memory.ownerId.isEmpty()
                        "project" -> memory.ownerId == projectOwner(workspace) && memory.ownerId.isNotBlank()
                        "session" -> memory.ownerId == sessionId && sessionId.isNotBlank()
                        else -> false
                    }
                    if (!visible) return false to "Cannot delete a memory that does not belong to the current project or session"
                    agentContextDao.deleteMemoryById(id)
                    true to "Deleted memory id=$id"
                } else if (key != null) {
                    val scope = args["scope"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "global"
                    val ownerId = memoryOwner(scope, sessionId, workspace)
                        ?: return false to "scope supports only global/project/session; project/session also require the corresponding context"
                    agentContextDao.deleteMemoryByKey(key, scope, ownerId)
                    true to "Deleted memory [$scope] key=$key"
                } else {
                    false to "memory delete requires an id or key"
                }
            }
            else -> false to "Unknown memory action: $action"
        }
    }

    private fun memoryOwner(scope: String, sessionId: String, workspace: String): String? = when (scope) {
        "global" -> ""
        "project" -> projectOwner(workspace).takeIf { it.isNotBlank() }
        "session" -> sessionId.trim().takeIf { it.isNotBlank() }
        else -> null
    }

    private fun projectOwner(workspace: String): String = workspace.trim().trimEnd('/')

    /** 新鲜度判定：expiresAt 为 null 或晚于 now 视为新鲜（过期只降权，不删除）。 */
    private fun isFresh(memory: AgentMemoryEntity, now: Long): Boolean {
        val expires = memory.expiresAt
        return expires == null || expires > now
    }

    private fun AgentMemoryEntity.render(lineBreak: Boolean = false): String {
        val suffix = buildString {
            if (pinned) append(" pinned")
            if (expiresAt != null && !isFresh(this@render, System.currentTimeMillis())) append(" expired")
            append(" v${revision}")
        }
        val separator = if (lineBreak) "\n        " else " "
        return if (lineBreak) {
            "- [${scope}/${kind}] ${key}: ${value}${separator}(volatility=$volatility, freshness=$suffix)"
        } else {
            "- [${scope}/${kind}] ${key}: ${value}$separator(volatility=$volatility, freshness=$suffix)"
        }
    }

    private companion object {
        const val MAX_MEMORY_KEY_CHARS = 128
        const val MAX_MEMORY_VALUE_CHARS = 4_096
        const val MAX_MEMORY_QUERY_CHARS = 256
        const val MAX_MEMORIES_PER_OWNER = 100
        const val MAX_MEMORY_LIST = 100
        const val MAX_PINNED_CHARS = 1_500
    }

    suspend fun executePlan(args: JsonObject, sessionId: String): Pair<Boolean, String> {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "get"
        return when (action) {
            "replace_active", "set_active", "create" -> {
                val goal = args["goal"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "plan replace_active requires a goal description"
                val stepsElement = args["steps"]
                val stepsJson = stepsElement?.toString() ?: "[]"
                agentContextDao.savePlan(
                    AgentPlanEntity(
                        sessionId = sessionId,
                        goal = goal,
                        stepsJson = stepsJson,
                        status = "active",
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                true to "Created/updated the task execution plan:\nGoal: $goal\nSteps: $stepsJson\n\nContinue according to the plan. After each step, call plan(action=\"advance\", ...) to update its status; use replace_active to revise the plan if blocked; call clear_active when all steps are complete."
            }
            "get_active", "get" -> {
                val plan = agentContextDao.getActivePlan(sessionId)
                if (plan == null) {
                    true to "No active task plan in this session"
                } else {
                    true to "Active task plan:\nGoal: ${plan.goal}\nSteps: ${plan.stepsJson}\nStatus: ${plan.status}"
                }
            }
            "advance", "update_steps" -> {
                val plan = agentContextDao.getActivePlan(sessionId)
                    ?: return false to "There is no active plan to advance in this session. Create one with replace_active first."
                val stepsElement = args["steps"]
                val newStepsJson = stepsElement?.toString() ?: plan.stepsJson
                // status 是计划生命周期枚举（active/completed/cancelled），不是自然语言进度描述。
                // 模型常把阶段说明塞进 status，直接写入会导致 getActivePlan 永久找不到记录。
                val requestedStatus = args["status"]?.jsonPrimitive?.contentOrNull?.lowercase()
                val newStatus = when (requestedStatus) {
                    "completed", "cancelled", "active" -> requestedStatus
                    else -> plan.status
                }
                agentContextDao.savePlan(
                    plan.copy(
                        stepsJson = newStepsJson,
                        status = newStatus,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                true to "[Plan progress synchronized]\nStatus: $newStatus\nCurrent steps: $newStepsJson\nContinue with the remaining tasks."
            }
            "clear_active", "clear" -> {
                agentContextDao.deletePlanBySession(sessionId)
                true to "Cleared the task plan for this session"
            }
            else -> false to "Unknown plan action: $action"
        }
    }

    suspend fun executeScratchpad(args: JsonObject, sessionId: String): Pair<Boolean, String> {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        return when (action) {
            "save", "set" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "scratchpad save requires a key"
                val value = args["value"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "scratchpad save requires a value"
                agentContextDao.saveScratchpad(
                    AgentScratchpadEntity(
                        sessionId = sessionId,
                        key = key,
                        value = value,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                true to "Saved scratchpad entry [$key] = $value"
            }
            "get" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "scratchpad get requires a key"
                val item = agentContextDao.getScratchpad(sessionId, key)
                if (item == null) {
                    true to "Scratchpad entry [$key] does not exist"
                } else {
                    true to "Scratchpad [$key]: ${item.value}"
                }
            }
            "list" -> {
                val list = agentContextDao.listScratchpads(sessionId)
                if (list.isEmpty()) {
                    true to "No scratchpad entries yet"
                } else {
                    val formatted = list.joinToString("\n") { "- [${it.key}]: ${it.value}" }
                    true to "Current scratchpad:\n$formatted"
                }
            }
            "delete" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "scratchpad delete requires a key"
                agentContextDao.deleteScratchpad(sessionId, key)
                true to "Deleted scratchpad entry [$key]"
            }
            "clear" -> {
                agentContextDao.clearScratchpads(sessionId)
                true to "Cleared all scratchpad entries for this task"
            }
            else -> false to "Unknown scratchpad action: $action"
        }
    }
}
