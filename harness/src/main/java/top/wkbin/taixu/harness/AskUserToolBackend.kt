package top.wkbin.taixu.harness

import java.util.UUID
import top.wkbin.taixu.core.database.AgentApprovalRepository
import top.wkbin.taixu.harness.core.ToolBackend

data class AskUserRequest(
    val toolCall: ToolCall,
    val sessionId: String,
    val workspace: String,
    val operationId: String?,
    /** 子智能体 Lane 没有可暂停的审批 UI，为 false 时走 approvalDeferred 中性结果。 */
    val allowApprovalRequest: Boolean,
    val now: Long,
)

/**
 * Agent 工具后端：ask_user 向用户提问。
 *
 * ask_user 是向用户提问的动作本身，不走审批门控（策略引擎对它亦豁免）：
 * 校验参数 → 落一条 pending 请求（复用审批请求的暂停/恢复管道）→ 返回
 * awaitingApproval 让整轮暂停；UI 渲染问题卡，答案由 HarnessLoop.resolveQuestion
 * 直接作为工具结果落库并续跑，无需重执行。
 *
 * 后台子智能体 Lane 不支持暂停：返回 approvalDeferred 的中性结果、不创建问题请求，
 * 由 Lane 收集成待办上交父智能体。
 */
class AskUserToolBackend(
    private val approvalPolicyEngine: ApprovalPolicyEngine,
    private val approvalRepository: AgentApprovalRepository? = null,
) : ToolBackend<AskUserRequest, ToolResult> {

    override suspend fun execute(request: AskUserRequest): ToolResult {
        if (!request.allowApprovalRequest) {
            return ToolResult(
                id = UUID.randomUUID().toString(),
                createdAt = request.now,
                toolCallId = request.toolCall.id,
                success = false,
                output = "This tool requires user input, but the current subagent lane cannot pause and wait for it, so no question request was created. Hand the question back to the primary Agent and invoke ask_user from the main session.",
                approvalDeferred = true,
            )
        }
        fun failure(output: String) = ToolResult(
            id = UUID.randomUUID().toString(),
            createdAt = request.now,
            toolCallId = request.toolCall.id,
            success = false,
            output = output,
        )
        if (request.sessionId.isBlank()) return failure("ask_user requires session context (sessionId is empty)")
        val questions = runCatching { AskUserQuestions.parse(request.toolCall.args) }.getOrElse { error ->
            return failure("ask_user parameter validation failed: ${error.message}. Fix the parameters and try again.")
        }
        val repository = approvalRepository ?: return failure("Approval repository is not initialized; unable to ask the user")
        val approvalRequest = approvalPolicyEngine.createRequest(
            sessionId = request.sessionId,
            toolCall = ToolCall(
                id = request.toolCall.id,
                createdAt = request.now,
                tool = HarnessTool.ASK_USER,
                args = request.toolCall.args,
                rawToolName = AskUserQuestions.TOOL_NAME,
            ),
            workspace = request.workspace,
            decision = ApprovalDecision(
                required = true,
                riskLevel = "none",
                reason = questions.first().question.take(200),
                summary = "The Agent has questions for you (${questions.size} questions)",
            ),
            operationId = request.operationId,
        )
        // 问题等待的是用户的思考与决策，TTL 比操作类审批（10 分钟）宽裕
        runCatching { repository.create(approvalRequest.copy(expiresAt = request.now + QUESTION_TTL_MS)) }
            .getOrElse { throwable ->
                return failure("Failed to save the question request: ${throwable.message ?: throwable::class.simpleName}")
            }
        return ToolResult(
            id = UUID.randomUUID().toString(),
            createdAt = request.now,
            toolCallId = request.toolCall.id,
            success = true,
            output = "Asked the user ${questions.size} questions and is waiting for answers.",
            awaitingApproval = true,
            approvalRequestId = approvalRequest.id,
        )
    }

    companion object {
        /** ask_user 问题请求的有效期：等待用户思考与决策，比操作类审批的 10 分钟宽裕。 */
        const val QUESTION_TTL_MS: Long = 30 * 60 * 1000L
    }
}