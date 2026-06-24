package smartagent.agent.toolcalling

import kotlinx.serialization.json.jsonPrimitive
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
    private val maxIterations: Int = 5
) {
    fun run(userQuery: String, priorHistory: List<Message> = emptyList()): String {
        val tools = session.listTools()
        val toolSchema = formatToolsForPrompt(serverName, tools)

        val systemPrompt = buildSystemPrompt(toolSchema)
        val messages = mutableListOf(Message("system", systemPrompt))
        messages += priorHistory
        messages += Message("user", userQuery)

        repeat(maxIterations) { iteration ->
            val spinner = Spinner("${Colors.DARK_GRAY}Обрабатываю${Colors.RESET}")
            val response = gateway.chat(messages, model, "tool-calling").also { spinner.stop() }
                ?: return "LLM returned no response."
            val raw = response.content

            when (val decision = parseDecision(raw)) {
                is ToolCallDecision.FinalAnswer -> return decision.text

                is ToolCallDecision.ParseError -> {
                    // Treat as final answer if we can't parse structure
                    return decision.raw
                }

                is ToolCallDecision.CallTool -> {
                    println("${Colors.DARK_GRAY}[SYSTEM] Using tool: $serverName/${decision.toolName}${Colors.RESET}")

                    // Coerce JsonObject argument values to String for McpSession
                    val args = decision.arguments.entries.associate { (k, v) ->
                        k to runCatching { v.jsonPrimitive.content }.getOrElse { v.toString() }
                    }

                    val toolResult = runCatching {
                        val element = session.callTool(decision.toolName, args)
                        if (element != null) {
                            println("${Colors.DARK_GRAY}[SYSTEM] Tool completed successfully${Colors.RESET}")
                            renderToolResult(element)
                        } else {
                            println("${Colors.DARK_GRAY}[SYSTEM] Tool failed: no result returned${Colors.RESET}")
                            "[tool returned no result]"
                        }
                    }.getOrElse { e ->
                        println("${Colors.DARK_GRAY}[SYSTEM] Tool failed: ${e.message}${Colors.RESET}")
                        "[tool error: ${e.message}]"
                    }

                    messages += Message("assistant", raw)
                    messages += Message("user", "Tool ${decision.toolName} returned:\n$toolResult")
                }
            }
        }

        return "Agent exceeded maximum tool iterations."
    }

    private fun buildSystemPrompt(toolSchema: String) = """
You are an AI assistant with access to MCP tools. MCP tools are the ONLY way to read or modify external state.

Available tools on server "$serverName":

$toolSchema

---

CORE PRINCIPLE:

* MCP tools are the source of truth
* If something can be stored, scheduled, or retrieved → you MUST use a tool

---

REMINDER POLICY (IMPORTANT):
If the user expresses intent like:

* "remind me"
* "remember to"
* "in X minutes/hours"
* "tomorrow at..."
* "каждый день / every day"
* any scheduling or future action request

YOU MUST:

1. Extract:

   * text of reminder
   * execution time (resolve relative time)
2. ALWAYS call `create_reminder`
3. NEVER answer with only FINAL_ANSWER for reminder creation requests

Examples:

User: "remind me to call mom in 30 minutes"
→ TOOL_CALL create_reminder

User: "напомни позвонить маме через час"
→ TOOL_CALL create_reminder

---

TOOL CALL FORMAT:
Respond with EXACTLY:

TOOL_CALL
tool=<tool_name>
arguments={"key": "value"}

RULES:

* Arguments MUST be valid JSON
* Never include explanations in TOOL_CALL
* One tool call per response

---

FINAL ANSWER RULES:
Use FINAL_ANSWER only when:

* no tool is needed
* or after tool results are received

Do not mention internal tools in FINAL_ANSWER.

---

DECISION RULE:

* If action/state change is needed → TOOL_CALL
* If information request → TOOL_CALL if tools exist
* Otherwise → FINAL_ANSWER

---

STRICTNESS:

* Do not guess time offsets if unsure → still call create_reminder with best-effort ISO timestamp
* Do not ask clarification for time unless absolutely ambiguous
* Prefer tool usage over reasoning in text
  """.trimIndent()
}
