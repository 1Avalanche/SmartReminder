package smartagent.agent.toolcalling

import kotlinx.serialization.json.jsonPrimitive
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import smartagent.Colors
import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import smartagent.Spinner
import smartagent.mcp_handler.McpSession
import smartagent.mcp_handler.renderToolResult

class ToolCallingLoop(
    private val serverName: String,
    private val session: McpSession,
    private val gateway: LLMGateway,
    private val model: ModelConfig,
    private val maxIterations: Int = 5,
    private val chatId: Long? = null
) {
    fun run(userQuery: String, priorHistory: List<Message> = emptyList()): String {
        val tools = session.listTools()
        val toolSchema = formatToolsForPrompt(serverName, tools)

        val systemPrompt = buildSystemPrompt(toolSchema)
        val messages = mutableListOf(Message("system", systemPrompt))
        messages += priorHistory
        messages += Message("user", userQuery)

        repeat(maxIterations) { iteration ->
            println("[ToolLoop] iteration=${iteration + 1}/${maxIterations}")

            val spinner = Spinner("${Colors.DARK_GRAY}Обрабатываю${Colors.RESET}")
            val response = gateway.chat(messages, model, "tool-calling").also { spinner.stop() }
            if (response == null) {
                println("[ToolLoop][ERROR] LLM returned null on iteration=${iteration + 1}")
                return "LLM returned no response."
            }
            val raw = response.content
            println("[ToolLoop] raw_length=${raw.length} raw_preview=${raw.take(200).replace("\n", "↵")}")

            when (val decision = parseDecision(raw)) {
                is ToolCallDecision.FinalAnswer -> {
                    println("[ToolLoop] decision=FinalAnswer")
                    return decision.text
                }

                is ToolCallDecision.ParseError -> {
                    val r = decision.raw
                    println("[ToolLoop] decision=ParseError raw=${r.take(300).replace("\n", "↵")}")

                    if (r.contains("TOOL_CALL") || r.contains("<invoke")) {
                        println("[ToolLoop][WARN] Malformed tool call syntax, re-prompting")
                        messages += Message("assistant", r)
                        messages += Message("user", "Malformed tool call. Use the exact format:\nTOOL_CALL\ntool=<name>\narguments={...}\nOr FINAL_ANSWER if no tool needed.")
                        return@repeat
                    }

                    // Preamble detection: LLM started a sentence but didn't complete the protocol
                    val looksIncomplete = r.trimEnd().endsWith(":") || r.length < 40
                    if (looksIncomplete) {
                        println("[ToolLoop][WARN] Response looks like preamble (len=${r.length}), re-prompting")
                        messages += Message("assistant", r)
                        messages += Message("user", "Respond with TOOL_CALL or FINAL_ANSWER. Do not write any text before your response.")
                        return@repeat
                    }

                    // Plain complete text — return as final answer
                    return r
                }

                is ToolCallDecision.CallTool -> {
                    println("[ToolLoop] decision=CallTool tool=${decision.toolName} args=${decision.arguments}")

                    val args = decision.arguments.entries.associate { (k, v) ->
                        k to runCatching { v.jsonPrimitive.content }.getOrElse { v.toString() }
                    }.toMutableMap()

                    // Inject chat_id from Telegram context — never ask the user
                    if (chatId != null && "chat_id" !in args) {
                        args["chat_id"] = chatId.toString()
                        println("[ToolLoop] injected chat_id=$chatId")
                    }

                    println("[ToolLoop] calling tool=${decision.toolName} resolved_args=$args")

                    val toolResult = runCatching {
                        val element = session.callTool(decision.toolName, args)
                        if (element != null) {
                            val rendered = renderToolResult(element)
                            println("[ToolLoop] tool_result=${rendered.take(300)}")
                            rendered
                        } else {
                            println("[ToolLoop][ERROR] Tool=${decision.toolName} returned null element")
                            "[tool returned no result]"
                        }
                    }.getOrElse { e ->
                        println("[ToolLoop][ERROR] Tool=${decision.toolName} threw exception: ${e.message}")
                        e.printStackTrace()
                        "[tool error: ${e.message}]"
                    }

                    messages += Message("assistant", raw)
                    messages += Message("user", "Tool ${decision.toolName} returned:\n$toolResult")
                }
            }
        }

        println("[ToolLoop][ERROR] Exceeded maxIterations=$maxIterations")
        return "Agent exceeded maximum tool iterations."
    }

    private fun buildSystemPrompt(toolSchema: String): String {
        val now = ZonedDateTime.now()
        val nowStr = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val tz = now.zone.id

        val chatIdLine = if (chatId != null) """
- Telegram chat_id = $chatId
- For any tool that accepts chat_id: use $chatId automatically
- Do NOT mention chat_id to the user""" else ""

        val contextBlock = """

---

SYSTEM CONTEXT (injected — NEVER ask the user about this):
- Current date and time: $nowStr
- Timezone: $tz
- Resolve relative time expressions ("через 2 минуты", "in an hour", "tomorrow at 9") using this timestamp$chatIdLine
"""

        return """
You are an AI assistant with access to MCP tools. MCP tools are the ONLY way to read or modify external state.

Available tools on server "$serverName":

$toolSchema
$contextBlock
---

CORE PRINCIPLE:

* MCP tools are the source of truth
* Examine available tools and their descriptions carefully
* Pick the tool that best fits the user's intent
* If no available tool can fulfill the request → use FINAL_ANSWER to say so clearly
* Do NOT invent tool names — only call tools listed above

---

TOOL CALL FORMAT:
Respond with EXACTLY:

TOOL_CALL
tool=<tool_name>
arguments={"key": "value"}

CRITICAL RULES:

* Arguments MUST be valid JSON
* Never include explanations in TOOL_CALL
* One tool call per response
* Do NOT write ANY text before TOOL_CALL — start your response directly with the keyword
* Wrong: "Создаю напоминание:\nTOOL_CALL..."
* Correct: "TOOL_CALL\ntool=..."
* NEVER output tool calls as XML, HTML, or any format other than the TOOL_CALL block above
* NEVER expose tool call syntax, XML, or JSON payloads to the user

---

FINAL ANSWER RULES:
Use FINAL_ANSWER only when:

* no tool is needed or available for the request
* or after tool results are received

Format: start directly with FINAL_ANSWER, then your text.
Do not mention internal tools in FINAL_ANSWER.

---

DECISION RULE:

* If action/state change is needed → find the right TOOL_CALL
* If no suitable tool exists → FINAL_ANSWER explaining limitation
* Otherwise → FINAL_ANSWER
  """.trimIndent()
    }
}
