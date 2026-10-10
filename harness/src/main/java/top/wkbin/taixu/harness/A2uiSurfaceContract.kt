package top.wkbin.taixu.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * render_surface 工具对 LLM 暴露的契约（名称 / 描述 / 参数 Schema / ApiToolDefinition）。
 *
 * 单独成文件的原因：ProviderClient 的 TOOLS 列表在尺寸棘轮基线内（只许缩减），
 * 这里把定义收敛为一个常量，ProviderClient 只需追加一行引用。
 */
object A2uiSurfaceContract {

    const val TOOL_NAME = "render_surface"

    /**
     * 太墟目录 id。createSurface 必须使用它。目录包含官方 Basic Catalog，并增加 Table。
     * [LEGACY_CATALOG_ID] 是官方 basic catalog；渲染器会把它改写成 [CATALOG_ID]，旧载荷仍能显示。
     */
    const val CATALOG_ID = "https://taixu.app/a2ui/catalog/v1"

    const val LEGACY_CATALOG_ID = "https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json"

    private const val TOOL_DESCRIPTION =
        "Render structured data as a native interactive surface on the device. Use this tool for content suited to visualization (comparisons/rankings/" +
            "forms/dashboards), then summarize in one or two sentences without repeating the data in the response body." +
            "messages is a JSON string containing an array of A2UI messages: the first must be createSurface (use a new surfaceId for a new surface)," +
            "followed by updateComponents (flat components; containers reference child component IDs through children/child; exactly one root)," +
            "deleteSurface removes a surface. version is fixed at v0.9; catalogId is fixed to the TaiXu catalog. Do not double-escape messages;" +
            "text must be a literal constant, not an expression. Do not invent component types." +
            "Use Table for comparisons and rankings: columns is an array of up to 6 header strings; rows is an array of up to 12 objects with a cells string array; cells and columns must have equal lengths." +
            "For other content, use the Basic Catalog: Text/Row/Column/List/Card/Tabs/Modal/Divider/Button/" +
            "TextField/CheckBox/ChoicePicker/Slider/DateTimeInput/Image/Video/AudioPlayer。" +
            "User interactions with the surface are returned as [A2UI Surface Event] messages. Handle them accordingly or update the surface using the same surfaceId."

    private const val TOOL_SCHEMA_JSON =
        """{"type":"object","properties":{"surfaceId":{"type":"string","description":"Unique surface ID (letters, digits, _, ., :, or -; up to 64 characters); reuse the existing ID when updating a surface"},"title":{"type":"string","description":"Short surface title (up to 80 characters)"},"messages":{"type":"string","description":"A2UI 协议消息数组的 JSON 字符串；不要二次转义，version 固定为 v0.9，catalogId 固定为 $CATALOG_ID。示例：[{\"version\":\"v0.9\",\"createSurface\":{\"surfaceId\":\"s1\",\"catalogId\":\"$CATALOG_ID\"}},{\"version\":\"v0.9\",\"updateComponents\":{\"surfaceId\":\"s1\",\"components\":[{\"id\":\"root\",\"component\":\"Column\",\"children\":[\"t\"]},{\"id\":\"t\",\"component\":\"Text\",\"text\":\"你好\"}]}}]"}},"required":["surfaceId","messages"]}"""

    /** ProviderClient.TOOLS 直接引用，避免在棘轮基线文件里展开多行定义。 */
    val TOOL_DEFINITION: ApiToolDefinition = ApiToolDefinition(
        function = ApiFunctionDefinition(
            name = TOOL_NAME,
            description = TOOL_DESCRIPTION,
            parameters = Json.parseToJsonElement(TOOL_SCHEMA_JSON) as JsonObject,
        ),
    )
}
