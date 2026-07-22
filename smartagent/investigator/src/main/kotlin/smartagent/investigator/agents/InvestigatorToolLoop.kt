package smartagent.investigator.agents

import kotlinx.serialization.json.jsonPrimitive
import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import smartagent.agent.toolcalling.ToolCallDecision
import smartagent.agent.toolcalling.formatToolsForPrompt
import smartagent.agent.toolcalling.parseDecision
import smartagent.mcp_handler.McpSession
import smartagent.mcp_handler.renderToolResult

data class ToolLoopResult(val answer: String, val accessedFiles: List<String>)

private val ALLOWED_TOOLS = setOf("search_code", "get_file_contents")

// MiniMax wraps tool calls in <minimax:tool_call>...</minimax:tool_call>
// Inner content uses the same tool=/arguments= format as TOOL_CALL block
private val MINIMAX_TOOL_CALL = Regex(
    "<minimax:tool_call>([\\s\\S]*?)</minimax:tool_call>",
    setOf(RegexOption.IGNORE_CASE)
)

private fun preprocessRaw(raw: String): String {
    val match = MINIMAX_TOOL_CALL.find(raw) ?: return raw
    val inner = match.groupValues[1].trim()
    // inner already has "tool=..." and "arguments=..." lines
    return if (inner.startsWith("tool=")) "TOOL_CALL\n$inner" else inner
}

class InvestigatorToolLoop(
    private val session: McpSession,
    private val gateway: LLMGateway,
    private val model: ModelConfig,
    private val agentSystemPrompt: String,
    private val maxIterations: Int = 12
) {
    fun run(query: String, history: List<Message> = emptyList()): ToolLoopResult {
        val tools = session.listTools().filter { it.name in ALLOWED_TOOLS }
        if (tools.isEmpty()) return ToolLoopResult(
            "Ошибка: MCP-сессия не содержит инструментов search_code / get_file_contents. Проверь подключение к GitHub MCP серверу.",
            emptyList()
        )
        val toolSchema = formatToolsForPrompt(tools)
        val systemPrompt = buildSystemPrompt(toolSchema)

        val messages = mutableListOf<Message>()
        messages += Message("system", systemPrompt)
        messages += history
        messages += Message("user", query)

        val accessedFiles = mutableListOf<String>()
        val fileCache = FileContentCache()

        repeat(maxIterations) {
            val response = gateway.chat(messages, model, "investigator-tool")
                ?: return ToolLoopResult("Ошибка: LLM не вернул ответ.", accessedFiles)

            val raw = response.content
            val processed = preprocessRaw(raw)

            when (val decision = parseDecision(processed)) {
                is ToolCallDecision.FinalAnswer -> return ToolLoopResult(decision.text, accessedFiles)

                is ToolCallDecision.CallTool -> {
                    val toolName = decision.toolName
                    messages += Message("assistant", stripThinkBlocks(raw))

                    if (toolName !in ALLOWED_TOOLS) {
                        messages += Message("user", "Инструмент '$toolName' недоступен. Используй только: ${ALLOWED_TOOLS.joinToString(", ")}.")
                        return@repeat
                    }

                    var cachedFilePath: String? = null
                    if (toolName == "get_file_contents") {
                        val filePath = decision.arguments["path"]?.jsonPrimitive?.content
                            ?: decision.arguments["file_path"]?.jsonPrimitive?.content
                        if (filePath != null) {
                            accessedFiles += filePath
                            cachedFilePath = filePath
                            val cached = fileCache.get(filePath)
                            if (cached != null) {
                                messages += Message("user", "Результат $toolName:\n$cached")
                                return@repeat
                            }
                        }
                    }

                    val callResult = runCatching {
                        session.callTool(toolName, decision.arguments)?.let { renderToolResult(it) }
                    }

                    when {
                        callResult.isSuccess && callResult.getOrNull() != null -> {
                            val content = callResult.getOrNull()!!
                            if (cachedFilePath != null) fileCache.put(cachedFilePath, content)
                            messages += Message("user", "Результат $toolName:\n$content")
                        }
                        callResult.isSuccess ->
                            messages += Message("user", "Инструмент $toolName не вернул результатов.")
                        else -> {
                            val err = callResult.exceptionOrNull()?.message ?: "неизвестная ошибка"
                            System.err.println("[investigator] Tool $toolName failed: $err")
                            messages += Message("user", "Инструмент $toolName завершился с ошибкой: $err. Попробуй другой подход.")
                        }
                    }
                }

                is ToolCallDecision.ParseError -> {
                    val r = decision.raw
                    val looksNatural = r.length > 60
                        && !r.contains("TOOL_CALL")
                        && !r.contains("<invoke")
                        && !r.contains("<minimax:")
                    if (looksNatural) return ToolLoopResult(r, accessedFiles)
                    messages += Message("assistant", stripThinkBlocks(raw))
                    messages += Message("user", "Формат ответа не распознан. Используй строго TOOL_CALL или FINAL_ANSWER.")
                }
            }
        }

        return ToolLoopResult("Агент исчерпал лимит итераций. Попробуйте переформулировать запрос.", accessedFiles)
    }

    private fun buildSystemPrompt(toolSchema: String) = """
$agentSystemPrompt

---

## Доступные инструменты

$toolSchema

---

## Формат вызова инструмента

TOOL_CALL
tool=<название_инструмента>
arguments={"ключ":"значение"}

Правила:
- Ответ начинается строго с TOOL_CALL
- arguments — валидный JSON
- Только один вызов за раз

## Формат финального ответа

FINAL_ANSWER
<JSON>

Правила:
- Используй FINAL_ANSWER только когда задача выполнена полностью
- Содержимое — строго JSON (без markdown-обёртки)
""".trimIndent()
}
