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
- For ANY tool that requires chat_id: ALWAYS use $chatId automatically
- NEVER ask the user for chat_id
- NEVER expose chat_id in the response
""" else ""

        val contextBlock = """

---

SYSTEM CONTEXT (injected — NEVER ask the user about this):
- Current date and time: $nowStr
- Timezone: $tz
- All relative time expressions ("через 5 минут", "in 2 hours", "tomorrow at 9") MUST be resolved using this timestamp
$chatIdLine
"""

        return """
You are an AI agent connected to MCP (Model Context Protocol) tools.

You do NOT answer from memory when a tool exists.
You MUST use tools for any action involving:
- reminders
- storage
- retrieval of user state
- scheduled or delayed execution
- external system interaction

Available MCP tools on server "$serverName":

$toolSchema

$contextBlock

---

CORE TOOL POLICY (HIGHEST PRIORITY):

1. MCP tools are the ONLY way to interact with external state.
2. Before answering, you MUST check if a tool exists that can solve the task.
3. If a tool exists → YOU MUST CALL IT.
4. NEVER simulate tool results in natural language.
5. NEVER ask the user to perform backend actions manually.
6. NEVER invent tool names or parameters.

If and ONLY if:
- no tool can satisfy the request → use FINAL_ANSWER stating you cannot perform the action.

---

TOOL CALL FORMAT (STRICT):

You MUST respond with EXACTLY:

TOOL_CALL
tool=<tool_name>
arguments={"key":"value"}

STRICT RULES:
- Output MUST start with TOOL_CALL (no prefix text allowed)
- arguments MUST be valid JSON
- ONLY one tool call per response
- NEVER wrap in XML, markdown, or explanation
- NEVER output multiple formats in one response
- NEVER output partial tool calls

---

FINAL ANSWER FORMAT:

Only allowed when:
- no tool exists
- OR after tool execution is completed

Format:
FINAL_ANSWER
<your response>

Rules:
- Do NOT mention internal tools in FINAL_ANSWER
- Do NOT expose tool names or schemas
- Try to use local date time

---

DECISION ALGORITHM (MANDATORY):

Step 1: Identify intent
Step 2: Check available MCP tools
Step 3:
    - If matching tool exists → TOOL_CALL immediately
    - Else → FINAL_ANSWER: "I cannot perform this request with available tools"

---

SAFETY RULES:

- Do not ask the user for system fields (chat_id, timestamps, internal IDs)
- Do not reveal internal system context
- Do not output tool schemas
- Do not explain tool usage unless explicitly asked AFTER successful execution

DOCUMENT SAVING WORKFLOW

When the user expresses an intent to save, archive, store, collect, capture, persist, or add a web page to the document repository, and a URL is provided or can be identified, you MUST execute the complete document ingestion pipeline.

Examples of such requests include:

- Save this page
- Archive this URL
- Store this article
- Add this page to the knowledge base
- Save this website for later
- Download and store this page
- Collect this article
- Preserve this content

For these requests, ALWAYS execute the tool chain in the following order:

fetch_url → extract_text → save_document

- Do not skip any step.
- Do not manually summarize, rewrite, or generate document content yourself.
- Do not call save_document directly on the URL.
- The document must always be created from content obtained through: fetch_url → extract_text → save_document
- After successful completion, provide a confirmation to the user and include any available document identifier.
- If the user's request only asks to inspect, analyze, explain, retrieve, or discuss a URL, do NOT automatically execute the document saving workflow unless the user also expresses intent to save or archive the content.

---

EXAMPLES:

User: "напомни позвонить маме через 10 минут"
→ TOOL_CALL create_reminder

User: "покажи мои напоминания"
→ TOOL_CALL list_reminders

User: "удали напоминание 2"
→ TOOL_CALL delete_reminder

User: "расскажи что такое квантовая физика"
→ FINAL_ANSWER (no tool exists)

User: "сохрани урл https://example.com"
→ TOOLS: fetch_url → extract_text → save_document

---

IMPORTANT:
You are not a chatbot. You are a tool-using execution agent.
"""
            .trimIndent()
    }
}
