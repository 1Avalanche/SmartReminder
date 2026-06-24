package smartagent.agent.toolcalling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

sealed class ToolCallDecision {
    data class CallTool(val toolName: String, val arguments: JsonObject) : ToolCallDecision()
    data class FinalAnswer(val text: String) : ToolCallDecision()
    data class ParseError(val raw: String) : ToolCallDecision()
}

private val lenientJson = Json { ignoreUnknownKeys = true }

fun parseDecision(response: String): ToolCallDecision {
    val trimmed = response.trim()

    val toolCallIdx = trimmed.indexOf("TOOL_CALL")
    val finalAnswerIdx = trimmed.indexOf("FINAL_ANSWER")

    return when {
        toolCallIdx >= 0 && (finalAnswerIdx < 0 || toolCallIdx <= finalAnswerIdx) -> {
            parseToolCall(trimmed.substring(toolCallIdx))
        }
        finalAnswerIdx >= 0 -> {
            val after = trimmed.substring(finalAnswerIdx + "FINAL_ANSWER".length).trim()
            ToolCallDecision.FinalAnswer(after)
        }
        else -> ToolCallDecision.ParseError(trimmed)
    }
}

private fun parseToolCall(block: String): ToolCallDecision {
    val lines = block.lines()
    var toolName: String? = null
    var arguments: JsonObject? = null

    for (line in lines) {
        val l = line.trim()
        when {
            l.startsWith("tool=") -> toolName = l.removePrefix("tool=").trim()
            l.startsWith("arguments=") -> {
                val json = l.removePrefix("arguments=").trim()
                arguments = runCatching {
                    lenientJson.parseToJsonElement(json) as? JsonObject
                }.getOrNull()
            }
        }
    }

    val name = toolName ?: return ToolCallDecision.ParseError(block)
    return ToolCallDecision.CallTool(
        toolName = name,
        arguments = arguments ?: JsonObject(emptyMap())
    )
}
