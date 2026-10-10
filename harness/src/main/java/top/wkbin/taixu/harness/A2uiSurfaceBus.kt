package top.wkbin.taixu.harness

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * render_surface 工具的界面载荷总线。
 *
 * A2UI = Agent-to-UI：智能体不写代码，而是输出 JSON Lines 协议消息描述界面，
 * 客户端用 Compose 渲染器（feature:a2uipoc 模块）映射为原生组件。安全边界来自
 * Component Catalog：智能体只能使用目录里声明的组件，与工具白名单同构。
 *
 * 进程级入口仍是这个 object：ToolExecutor 与 Koin 模块都已顶着行数棘轮，不再为注入加行。
 * 会话绑定不靠那份最多 8 条的预览缓存——每条 surface 另有路由，直到所属会话被删除。
 *
 * 深度校验走钩子注入（[installDeepValidator]）：官方 A2UI parser 只允许在
 * feature:a2uipoc 使用（architecture-policy），harness 保持零 a2ui 依赖，
 * 由 a2uipoc 启动时注册基于官方 parser 的真校验，失败原因回传给模型自我纠正。
 */
object A2uiSurfaceBus {

    /** 工具产出的一条界面载荷（一次 render_surface 调用 = 一个 surface 的协议消息集）。 */
    data class A2uiSurfacePayload(
        val surfaceId: String,
        val title: String,
        /** A2UI 协议消息数组的 JSON 字符串（已归一化，渲染器逐条 processInput）。 */
        val messagesJson: String,
        val messageCount: Int,
        /** 发起本次 render_surface 的会话 id；用户交互事件按它路由回原会话。 */
        val sessionId: String,
        val createdAt: Long,
    )

    /** 用户与 A2UI surface交互产生的事件（由渲染器从官方 outboundEvents 转发而来）。 */
    data class A2uiUserEvent(
        val surfaceId: String,
        val surfaceTitle: String,
        val sessionId: String,
        val componentId: String,
        val eventName: String,
        val context: Map<String, Any?>,
        val timestamp: Long,
        /**
         * 本事件所属 surface 的数据模型快照（表单提交值在这里，不在 [context]）。
         * 缺省 null：旧调用方与单测不用改；类型保持 Map，harness 不依赖 a2ui。
         */
        val dataModel: Map<String, Any?>? = null,
    )

    /** A2UI 引擎运行时错误（组件被目录校验拒绝、surface 状态异常等），同样回传给智能体。 */
    data class A2uiErrorEvent(
        val surfaceId: String,
        val surfaceTitle: String,
        val sessionId: String,
        val code: String,
        val message: String,
    )

    private val _surfaces = MutableStateFlow<List<A2uiSurfacePayload>>(emptyList())

    /** 最近发布的界面载荷（新的在前，最多 [MAX_CACHED_SURFACES] 条，同 surfaceId 覆盖旧条目）。 */
    val surfaces: StateFlow<List<A2uiSurfacePayload>> = _surfaces.asStateFlow()

    /** 预览列表淘汰后仍要能把点击送回原会话。surfaceId → 会话与标题。 */
    data class A2uiSurfaceRoute(val sessionId: String, val title: String)

    private val routes = LinkedHashMap<String, A2uiSurfaceRoute>(16, 0.75f, true)

    /** 引擎出站消息出口（用户交互/运行时错误）；由装配层（ChatViewModel）注册，路由回原会话。 */
    @Volatile
    var userEventSink: ((A2uiUserEvent) -> Unit)? = null

    @Volatile
    var errorEventSink: ((A2uiErrorEvent) -> Unit)? = null

    @Volatile
    private var deepValidator: ((String) -> String?)? = null

    /**
     * 注册深度校验器（a2uipoc 启动时调用）：入参为归一化后的 messages JSON，
     * 返回 null 表示受理通过，返回非 null 文案作为 render_surface 的失败原因回传给模型。
     */
    fun installDeepValidator(validator: ((String) -> String?)?) {
        deepValidator = validator
    }

    /**
     * 设置页「清空」：页面上的预览收起。注入示例属于 [PREVIEW_SESSION_ID]，从引擎拆掉；
     * 聊天卡片的会话路由保留，点击还能回到原会话。
     */
    fun clearPreview() {
        releaseSession(PREVIEW_SESSION_ID)
        _surfaces.value = emptyList()
    }

    /** 设置页注入的示例界面。不属于任何聊天会话，清空时整批释放。 */
    const val PREVIEW_SESSION_ID = "a2ui-preview"

    fun clear() {
        clearPreview()
        synchronized(routes) { routes.clear() }
    }

    /**
     * 会话结束时丢掉该会话的界面（含已从预览列表淘汰、仍占着路由的），
     * 并通知渲染器释放引擎里的 surface。其他会话的界面保留。
     */
    fun releaseSession(sessionId: String) {
        if (sessionId.isBlank()) return
        val fromList = _surfaces.value.filter { it.sessionId == sessionId }
            .map { engineSurfaceId(it.sessionId, it.surfaceId) }
        val surfaceIds = synchronized(routes) {
            val fromRoutes = routes.filterValues { it.sessionId == sessionId }.keys.toList()
            fromRoutes.forEach { routes.remove(it) }
            (fromList + fromRoutes).distinct()
        }
        if (surfaceIds.isEmpty()) return
        if (fromList.isNotEmpty()) {
            _surfaces.value = _surfaces.value.filterNot { it.sessionId == sessionId }
        }
        onSurfacesReleased?.invoke(surfaceIds)
    }

    /**
     * 点击与运行时错误用它找回会话。预览列表里有就用列表；被 8 条上限淘汰后仍走路由表。
     */
    fun routeFor(surfaceId: String): A2uiSurfaceRoute? {
        synchronized(routes) {
            routes[surfaceId]?.let { route ->
                routes[surfaceId] = route
                return route
            }
        }
        val listed = _surfaces.value.firstOrNull {
            engineSurfaceId(it.sessionId, it.surfaceId) == surfaceId
        } ?: return null
        val route = A2uiSurfaceRoute(listed.sessionId, listed.title)
        synchronized(routes) {
            routes[surfaceId] = route
            trimRoutes()
            return routes[surfaceId]
        }
    }

    /** 引擎内部 id。空会话保持原 id，便于旧调用方和设置页示例以外的空白路径。 */
    fun engineSurfaceId(sessionId: String, surfaceId: String): String =
        if (sessionId.isBlank() || surfaceId.isBlank()) surfaceId else "$sessionId:$surfaceId"

    /** 渲染器注册：会话释放后按 surfaceId 拆掉引擎状态。harness 不依赖 a2ui。 */
    @Volatile
    var onSurfacesReleased: ((List<String>) -> Unit)? = null

    fun findSurface(surfaceId: String): A2uiSurfacePayload? =
        _surfaces.value.firstOrNull { it.surfaceId == surfaceId }

    /** 协议 deleteSurface 之后：预览列表和点击路由都丢掉，引擎里的界面已经不在了。 */
    fun forgetEngineSurface(engineSurfaceId: String) {
        if (engineSurfaceId.isBlank()) return
        synchronized(routes) { routes.remove(engineSurfaceId) }
        _surfaces.value = _surfaces.value.filterNot {
            engineSurfaceId(it.sessionId, it.surfaceId) == engineSurfaceId
        }
    }

    /**
     * render_surface 工具执行入口：校验参数 → 登记载荷 → 返回给模型的结果文本。
     * 返回值约定与 ToolExecutor 一致（success to output）。
     */
    fun publishFromTool(args: JsonObject, sessionId: String = ""): Pair<Boolean, String> {
        val payload = parseToolArgs(args, sessionId).getOrElse { failure ->
            return false to "render_surface parameter validation failed: ${failure.message}. Fix the parameters and try again."
        }
        publish(payload)
        return true to buildString {
            append("Submitted A2UI surface “${payload.title}” (surfaceId=${payload.surfaceId}, ")
            append("${payload.messageCount} protocol messages). The surface has been rendered as native components in the user's chat stream;")
            append("to update it, call again with the same surfaceId and include only updateComponents messages;")
            append("to remove it, send a deleteSurface message. User interactions with the surface (such as button taps)")
            append("are returned to you as [A2UI Surface Event] messages.")
        }
    }

    private fun publish(payload: A2uiSurfacePayload) {
        val key = engineSurfaceId(payload.sessionId, payload.surfaceId)
        synchronized(routes) {
            routes[key] = A2uiSurfaceRoute(payload.sessionId, payload.title)
            trimRoutes()
        }
        _surfaces.value = (listOf(payload) + _surfaces.value.filterNot {
            it.sessionId == payload.sessionId && it.surfaceId == payload.surfaceId
        }).take(MAX_CACHED_SURFACES)
    }

    private fun trimRoutes() {
        while (routes.size > MAX_SURFACE_ROUTES) {
            val eldest = routes.entries.iterator().next().key
            routes.remove(eldest)
        }
    }

    /** 渲染器转发引擎出站消息的入口（用户交互 → userEventSink，运行时错误 → errorEventSink）。 */
    fun publishUserEvent(event: A2uiUserEvent) {
        userEventSink?.invoke(event)
    }

    fun publishErrorEvent(event: A2uiErrorEvent) {
        errorEventSink?.invoke(event)
    }

    /** 把用户交互事件格式化为注入 agent 会话的消息文本。表单值在 dataModel，不在 context。 */
    fun formatUserEvent(event: A2uiUserEvent): String = buildString {
        append("[A2UI Surface Event] The user interacted with “${event.surfaceTitle}” (surfaceId=${event.surfaceId})")
        append(": componentId=${event.componentId}, eventName=${event.eventName}")
        if (event.context.isNotEmpty()) append(", context=${event.context}")
        val model = event.dataModel
        if (model != null && model.isNotEmpty()) append(", form dataModel=${compactDataModel(model)}")
        append(". This is a user interaction event from an A2UI surface. Continue based on the event semantics;")
        append("to update the surface, call render_surface with the same surfaceId and only updateComponents messages.")
    }

    /** 数据模型可能整棵带回；超长时截断，避免一条界面事件撑爆会话。 */
    private fun compactDataModel(model: Map<String, Any?>): String {
        val text = model.toString()
        if (text.length <= MAX_DATA_MODEL_CHARS) return text
        return text.take(MAX_DATA_MODEL_CHARS) + "…(truncated)"
    }

    /** 把引擎运行时错误格式化为注入 agent 会话的消息文本（否则界面只会静默转圈）。 */
    fun formatErrorEvent(event: A2uiErrorEvent): String = buildString {
        append("[A2UI Surface Error] Surface “${event.surfaceTitle}” (surfaceId=${event.surfaceId})")
        append("render failed: [${event.code}] ${event.message}.")
        append("Fix the component definition (property names, types, and child references must match the catalog),")
        append("then call render_surface again with the same surfaceId and the complete components list, including the root component with id=root.")
    }

    internal fun parseToolArgs(args: JsonObject, sessionId: String = ""): Result<A2uiSurfacePayload> = runCatching {
        val surfaceId = args["surfaceId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(surfaceId.isNotEmpty()) { "缺少 surfaceId" }
        require(surfaceId.length <= MAX_ID_CHARS && SURFACE_ID_REGEX.matches(surfaceId)) {
            "surfaceId may contain only letters, digits, _, ., :, and -; maximum length is $MAX_ID_CHARS characters"
        }
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim().takeIf { !it.isNullOrBlank() } ?: "A2UI surface"
        require(title.length <= MAX_TITLE_CHARS) { "title must not exceed $MAX_TITLE_CHARS characters" }
        val rawMessages = args["messages"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(rawMessages.length in MIN_JSON_CHARS..MAX_JSON_CHARS) {
            "messages must be a JSON string containing an array of A2UI protocol messages (length $MIN_JSON_CHARS..$MAX_JSON_CHARS)"
        }
        val messagesJson = parseMessagesArray(rawMessages).toString()
        deepValidator?.invoke(messagesJson)?.let { error -> error("A2UI protocol validation failed: $error") }
        A2uiSurfacePayload(
            surfaceId = surfaceId,
            title = title,
            messagesJson = messagesJson,
            messageCount = Json.parseToJsonElement(messagesJson).let { it as JsonArray }.size,
            sessionId = sessionId,
            createdAt = System.currentTimeMillis(),
        )
    }

    /**
     * 把模型提交的 messages 原文归一化为协议消息数组的紧凑 JSON；解析失败返回 null。
     * 工具路径（校验）与聊天卡片路径（渲染）共用，保证两条路律试到同一份归一化载荷。
     */
    fun normalizeMessagesJson(raw: String): String? = runCatching {
        parseMessagesArray(raw).toString()
    }.getOrNull()

    /**
     * 解析 messages 原文为协议消息数组，附带对模型常见双转义错误的自动修复：
     * 1) 正常路径：直接是 JSON 数组；
     * 2) 整体被包成 JSON 字符串（双重编码）→ 剥一层再解析；
     * 3) 引号带多余反斜杠转义（[{\"version\"…）→ 去掉多余转义再解析。
     */
    private fun parseMessagesArray(raw: String): JsonArray {
        val direct = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        if (direct is JsonArray) return validateArrayShape(direct)
        if (direct is JsonPrimitive && direct.isString) {
            val unwrapped = runCatching { Json.parseToJsonElement(direct.content) }.getOrNull()
            if (unwrapped is JsonArray) return validateArrayShape(unwrapped)
        }
        if (raw.contains("\\\"")) {
            val repaired = runCatching { Json.parseToJsonElement(raw.replace("\\\"", "\"")) }.getOrNull()
            if (repaired is JsonArray) return validateArrayShape(repaired)
        }
        throw IllegalArgumentException(
            "messages must be a JSON string containing an array of A2UI protocol messages (first item createSurface," +
                "followed by updateComponents/deleteSurface); do not escape the messages parameter a second time," +
                "provide the raw array directly",
        )
    }

    private fun validateArrayShape(array: JsonArray): JsonArray {
        require(array.isNotEmpty() && array.all { it is JsonObject && it.containsKey("version") }) {
            "Each item in the messages array must be an A2UI protocol message object with a version"
        }
        array.forEach { message ->
            val version = (message as JsonObject)["version"]?.jsonPrimitive?.contentOrNull
            require(version in SUPPORTED_VERSIONS) {
                "Supported protocol versions: $SUPPORTED_VERSIONS (received: $version)"
            }
        }
        return array
    }

    /** 供 feature:a2uipoc 演示屏使用的内置示例（与 [A2uiSurfaceContract.CATALOG_ID] 对齐）。 */
    fun sampleMessagesJson(surfaceId: String = "demo-1"): String = buildSampleMessages(surfaceId)

    internal const val MAX_ID_CHARS = 64
    internal const val MAX_TITLE_CHARS = 80
    internal const val MIN_JSON_CHARS = 8
    internal const val MAX_JSON_CHARS = 200_000
    internal const val MAX_CACHED_SURFACES = 8

    /** 与渲染器去重表同量级：长会话里被预览列表淘汰的卡片仍要能把点击送回。 */
    internal const val MAX_SURFACE_ROUTES = 1024

    /** formatUserEvent 里 dataModel 文本的上限（字符）。 */
    internal const val MAX_DATA_MODEL_CHARS = 2_000
    internal val SURFACE_ID_REGEX = Regex("[A-Za-z0-9_.:-]+")

    /** 与官方 A2uiJsonMessageParser.SUPPORTED_VERSIONS 对齐，库升级时需联动核对。 */
    internal val SUPPORTED_VERSIONS = listOf("v0.9", "v0.9.1")

    // 注意：含模板引用，不能声明为 const val（棘轮文件外的新文件，保持 private fun 即可）
    private fun buildSampleMessages(surfaceId: String): String =
        """[{"version":"v0.9","createSurface":{"surfaceId":"$surfaceId","catalogId":"${A2uiSurfaceContract.CATALOG_ID}"}},
{"version":"v0.9","updateComponents":{"surfaceId":"$surfaceId","components":[
{"id":"root","component":"Column","children":["title","table"]},
{"id":"title","component":"Text","text":"TaiXu A2UI"},
{"id":"table","component":"Table","columns":["Item","Value"],"rows":[{"cells":["Protocol","v0.9"]},{"cells":["Catalog","Basic + Table"]}]}
]}}]"""
}
