package top.wkbin.taixu.harness

import top.wkbin.taixu.harness.events.AgentEventLogger

/** 空响应重试时丢掉更早的图片，只保留最近 [keep] 张。超大视觉请求原样重发不会恢复。 */
internal fun retainLatestImages(messages: List<ApiMessage>, keep: Int = 1): List<ApiMessage> {
    if (keep < 0 || messages.isEmpty()) return messages
    val drop = messages.indices.filter { messages[it].imageUrls.isNotEmpty() }.dropLast(keep).toSet()
    if (drop.isEmpty()) return messages
    return messages.mapIndexed { index, message ->
        if (index !in drop) message
        else message.copy(
            imageUrls = emptyList(),
            content = message.content.orEmpty() + "\n[Previous images omitted; retry with only the most recent image.]",
        )
    }
}

internal suspend fun stripEmptyResponseImages(
    attempt: Int,
    maxRetries: Int,
    messages: List<ApiMessage>,
    sessionId: String,
    logger: AgentEventLogger,
    error: Throwable,
): List<ApiMessage> {
    if (attempt != maxRetries) return messages
    val pending = messages.sumOf { it.imageUrls.size }
    if (pending <= 1) return messages
    logger.log(sessionId, "EmptyResponseImageStrip", "Repeated empty responses for a request with $pending images; retrying with only the most recent image", error)
    return retainLatestImages(messages, keep = 1)
}
