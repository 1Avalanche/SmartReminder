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
import smartagent.mcp_handler.McpTool
import smartagent.mcp_handler.renderToolResult

class ToolCallingLoop(
    private val sessions: Map<String, McpSession>,
    private val gateway: LLMGateway,
    private val model: ModelConfig,
    private val maxIterations: Int = 5,
    private val chatId: Long? = null
) {
    fun run(userQuery: String, priorHistory: List<Message> = emptyList()): String {
        val sessionTools: List<Pair<McpSession, McpTool>> = sessions.values
            .flatMap { session -> session.listTools().map { tool -> session to tool } }
        val allTools: List<McpTool> = sessionTools.map { it.second }
        val toolOwner: Map<String, McpSession> = sessionTools.associate { (session, tool) -> tool.name to session }
        val toolByName: Map<String, McpTool> = allTools.associateBy { it.name }

        sessionTools.groupBy { it.first.name }.forEach { (serverName, pairs) ->
            println("[ToolLoop] server=$serverName tools=${pairs.map { it.second.name }}")
        }

        val failureEngine = ToolFailureEngine(toolByName.keys.toSet())
        var parseErrorCount = 0

        val toolSchema = formatToolsForPrompt(allTools)
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

            // Pre-validation: XML format hard block
            if (OutputValidator.containsXml(raw)) {
                println("[ToolLoop][WARN] XML format detected, rejecting raw=${raw.take(200).replace("\n", "↵")}")
                parseErrorCount++
                if (parseErrorCount > OutputValidator.MAX_PARSE_RETRIES) {
                    println("[ToolLoop][ERROR] Max parse retries exceeded (xml), returning fallback")
                    return OutputValidator.FALLBACK_MESSAGE
                }
                messages += Message("assistant", raw)
                messages += Message("user", OutputValidator.xmlRejectionPrompt(raw))
                return@repeat
            }

            when (val decision = parseDecision(raw)) {
                is ToolCallDecision.FinalAnswer -> {
                    println("[ToolLoop] decision=FinalAnswer")
                    val text = decision.text
                    if (!OutputValidator.isSafeForUser(text)) {
                        println("[ToolLoop][ERROR] FinalAnswer failed safety check, returning fallback")
                        return OutputValidator.FALLBACK_MESSAGE
                    }
                    return text
                }

                is ToolCallDecision.ParseError -> {
                    val r = decision.raw
                    println("[ToolLoop] decision=ParseError raw=${r.take(300).replace("\n", "↵")}")
                    parseErrorCount++
                    if (parseErrorCount > OutputValidator.MAX_PARSE_RETRIES) {
                        println("[ToolLoop][ERROR] Max parse retries exceeded, returning fallback")
                        return OutputValidator.FALLBACK_MESSAGE
                    }

                    val reason = when {
                        r.contains("TOOL_CALL") || r.contains("<invoke") -> "malformed tool call syntax"
                        r.trimEnd().endsWith(":") || r.length < 40 -> "response looks like preamble"
                        else -> "unrecognized format"
                    }
                    println("[ToolLoop][WARN] ParseError reason=$reason retries=$parseErrorCount")
                    messages += Message("assistant", r)
                    messages += Message("user", OutputValidator.parseErrorRecoveryPrompt(r, reason))
                    return@repeat
                }

                is ToolCallDecision.CallTool -> {
                    println("[ToolLoop] decision=CallTool tool=${decision.toolName} args=${decision.arguments}")

                    val args = decision.arguments.entries.associate { (k, v) ->
                        k to runCatching { v.jsonPrimitive.content }.getOrElse { v.toString() }
                    }.toMutableMap()

                    val injected = ChatIdInjector.enrich(decision.toolName, args, chatId)
                    println("[ToolLoop] chat_id_injected=$injected tool=${decision.toolName}")

                    val toolDef = toolByName[decision.toolName]
                    val finalArgs = ChatIdInjector.stripUnknownArgs(toolDef?.inputSchema, args).toMutableMap()
                    if (finalArgs.size != args.size) {
                        println("[ToolLoop] stripped ${args.keys - finalArgs.keys} from tool=${decision.toolName}")
                    }

                    // Guard: disabled tool
                    if (failureEngine.isDisabled(decision.toolName)) {
                        val fallback = ToolFallbackStrategy.findAvailableFallback(decision.toolName, failureEngine.availableTools)
                        val msg = buildString {
                            appendLine("Tool '${decision.toolName}' is currently disabled due to a prior failure.")
                            if (fallback != null) appendLine("Use '$fallback' instead.")
                            else appendLine("No fallback available. Respond with FINAL_ANSWER if you cannot proceed.")
                        }.trimEnd()
                        println("[ToolFailure] blocked disabled tool=${decision.toolName} fallback=$fallback")
                        messages += Message("assistant", raw)
                        messages += Message("user", msg)
                        return@repeat
                    }

                    // Guard: identical retry
                    if (failureEngine.isAlreadyCalled(decision.toolName, finalArgs)) {
                        val msg = failureEngine.buildIdenticalRetryMessage(decision.toolName)
                        println("[ToolFailure] identical retry blocked: tool=${decision.toolName} args=$finalArgs")
                        messages += Message("assistant", raw)
                        messages += Message("user", msg)
                        return@repeat
                    }

                    val ownerSession = toolOwner[decision.toolName]
                    println("[ToolLoop] routing tool=${decision.toolName} → server=${ownerSession?.name ?: "unknown"} final_args=$finalArgs")
                    if (ownerSession == null) {
                        println("[ToolLoop][ERROR] Tool=${decision.toolName} not found in any connected server")
                        messages += Message("assistant", raw)
                        messages += Message("user", "Tool ${decision.toolName} is not available. Choose from available tools only.")
                        return@repeat
                    }

                    failureEngine.markCalled(decision.toolName, finalArgs)

                    val callResult = runCatching {
                        val element = ownerSession.callTool(decision.toolName, finalArgs)
                        if (element != null) {
                            val rendered = renderToolResult(element)
                            println("[ToolLoop] tool_result=${rendered.take(300)}")
                            rendered
                        } else {
                            println("[ToolLoop][WARN] Tool=${decision.toolName} returned null element")
                            null
                        }
                    }

                    when {
                        callResult.isSuccess && callResult.getOrNull() != null -> {
                            failureEngine.recordSuccess(decision.toolName)
                            messages += Message("assistant", raw)
                            messages += Message("user", "Tool ${decision.toolName} returned:\n${callResult.getOrNull()}")
                        }
                        callResult.isSuccess && callResult.getOrNull() == null -> {
                            // Empty result — treat as success with empty payload, not a failure
                            failureEngine.recordSuccess(decision.toolName)
                            messages += Message("assistant", raw)
                            messages += Message("user", "Tool ${decision.toolName} returned no result.")
                        }
                        else -> {
                            val e = callResult.exceptionOrNull()!!
                            val errorMsg = e.message ?: "unknown error"
                            println("[ToolFailure] tool=${decision.toolName} error=$errorMsg")
                            val failureType = failureEngine.recordFailure(decision.toolName, errorMsg)
                            val replan = failureEngine.buildReplanMessage(decision.toolName, failureType, errorMsg)
                            println("[ToolFailure] type=${failureType.name} replan=$replan")
                            messages += Message("assistant", raw)
                            messages += Message("user", replan)
                        }
                    }
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

Available MCP tools:

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

MULTI-MCP TOOL EXECUTION RULES:

Tools may come from different MCP servers.

Each tool belongs to exactly one MCP server.

You MUST treat tools as part of a distributed system.

You MUST NOT assume all tools are local.

---

TOOL PIPELINE RULES:

If a user request requires multiple steps across tools:

1. You MUST plan a sequence of tool calls
2. Each tool processes output of previous tool
3. Tools may belong to different MCP servers
4. You MUST NOT skip intermediate steps

Example pipelines:

Tavily → My MCP:
tavily_search → tavily_extract → save_document

My MCP only:
fetch_url → extract_text → save_document

---

CROSS-SERVER EXECUTION RULE:

If a tool returns data that can be processed by another tool from ANY MCP server:

You MUST continue execution with next tool.

Do NOT stop after first tool if the task is incomplete.

---

TOOL CALL FORMAT (STRICT):

You MUST respond with EXACTLY:

TOOL_CALL
tool=<tool_name>
arguments={"key":"value"}

STRICT RULES:
- Output MUST start with TOOL_CALL (no prefix text allowed)
- arguments MUST be valid JSON
- NEVER wrap in XML, markdown, or explanation
- NEVER output multiple formats in one response
- NEVER output partial tool calls

TOOL CALL EXECUTION MODEL:

You may execute multiple tool calls in sequence across multiple responses.

Each response contains ONLY ONE TOOL_CALL,
but execution MUST continue until the full pipeline is complete

---

TAVILY MCP USAGE RULE:

If task involves:
- finding information on the web
- searching for articles
- extracting content from unknown URLs

You MUST prefer:

tavily-search → tavily-extract

before using fetch_url from internal MCP.

---

Output Formatting Rules

Responses must be plain text optimized for Telegram chat. Assume all responses will be displayed in a Telegram chat on a mobile device.

Do NOT use:
- Markdown tables
- ASCII tables
- Pipe-separated tables
- HTML tables
- XML
- HTML formatting
- Code blocks unless explicitly requested

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

Pipeline can start from multiple sources:

A) Internal MCP:
fetch_url → extract_text → save_document

B) Tavily MCP:
tavily-search → tavily-extract → save_document

In both cases:
- final step MUST be save_document (internal MCP)

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
