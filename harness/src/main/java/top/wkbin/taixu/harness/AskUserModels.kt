package top.wkbin.taixu.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json

/**
 * ask_user 工具的参数模型（对齐 opencode question 工具）：
 * 模型一次可提 1~4 个问题，每题可带候选项（label + 说明）并允许自定义输入。
 * ToolExecutor 校验入参与 UI 渲染问题卡共用同一解析，避免两处口径漂移。
 */
@Serializable
data class AskUserQuestion(
    val question: String,
    val header: String? = null,
    val options: List<AskUserOption> = emptyList(),
    /** 有候选项时默认允许自定义输入；纯自由文本问题恒为 true。 */
    val allowCustom: Boolean = true,
)

@Serializable
data class AskUserOption(
    val label: String,
    val description: String? = null,
)

object AskUserQuestions {
    const val MAX_QUESTIONS = 4
    const val MAX_OPTIONS = 6
    const val TOOL_NAME = "ask_user"

    /** 解析并校验 ask_user 入参；不合法时抛 [IllegalArgumentException]（文案可直接回写模型）。 */
    fun parse(args: JsonObject): List<AskUserQuestion> {
        val rawQuestions = args["questions"] as? JsonArray
            ?: throw IllegalArgumentException("questions must be an array of question objects")
        if (rawQuestions.isEmpty() || rawQuestions.size > MAX_QUESTIONS) {
            throw IllegalArgumentException("questions array length must be between 1 and $MAX_QUESTIONS")
        }
        return rawQuestions.mapIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw IllegalArgumentException("questions[$index] must be a JSON object")
            val question = item["question"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (question.isEmpty()) {
                throw IllegalArgumentException("questions[$index].question cannot be empty")
            }
            val options = (item["options"] as? JsonArray)?.mapIndexed { optionIndex, optionElement ->
                val option = optionElement as? JsonObject
                    ?: throw IllegalArgumentException("questions[$index].options[$optionIndex] must be a JSON object")
                val label = option["label"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (label.isEmpty()) {
                    throw IllegalArgumentException("questions[$index].options[$optionIndex].label cannot be empty")
                }
                AskUserOption(
                    label = label,
                    description = option["description"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null },
                )
            }.orEmpty()
            if (options.size > MAX_OPTIONS) {
                throw IllegalArgumentException("questions[$index].options supports at most $MAX_OPTIONS options")
            }
            val duplicate = options.groupBy { it.label }.filterValues { it.size > 1 }.keys.firstOrNull()
            if (duplicate != null) {
                throw IllegalArgumentException("questions[$index] contains duplicate option “$duplicate”; use mutually exclusive options")
            }
            val allowCustom = item["allow_custom"]?.jsonPrimitive?.booleanOrNull ?: options.isNotEmpty()
            AskUserQuestion(
                question = question,
                header = item["header"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null },
                options = options,
                allowCustom = allowCustom || options.isEmpty(),
            )
        }
    }

    /** 把 UI 提交的答案（`[{"index":0,"answer":"..."}]`）格式化为回写给模型的工具结果。 */
    fun formatAnswers(questionsJson: String, answersJson: String): String {
        val questions = runCatching {
            parse(Json.parseToJsonElement(questionsJson).jsonObject)
        }.getOrNull().orEmpty()
        val answers = runCatching {
            (Json.parseToJsonElement(answersJson) as? JsonArray)
                ?.mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val index = item["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
                    val answer = item["answer"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (answer.isEmpty()) null else index to answer
                }
        }.getOrNull().orEmpty()
        if (questions.isEmpty() || answers.isEmpty()) {
            return "User answered the ask_user questions: $answersJson"
        }
        return buildString {
            append("User answered the ask_user questions:\n")
            answers.forEach { (index, answer) ->
                val question = questions.getOrNull(index)?.question ?: "Question ${index + 1}"
                append("${index + 1}. $question\n   → $answer\n")
            }
        }.trimEnd()
    }
}
