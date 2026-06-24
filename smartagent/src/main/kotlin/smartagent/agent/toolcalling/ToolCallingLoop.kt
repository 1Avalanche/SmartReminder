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
You are an AI assistant with access to MCP tools. Use them to answer the user's request.

Available tools on server "$serverName":

$toolSchema

To call a tool, respond with EXACTLY this format (nothing else):
TOOL_CALL
tool=<tool_name>
arguments={"key": "value"}

To give a final answer, respond with EXACTLY this format:
FINAL_ANSWER
<your answer here>

Rules:
- Use TOOL_CALL when you need to fetch data to answer the question.
- Use FINAL_ANSWER when you have enough information to respond.
- Never mix TOOL_CALL and FINAL_ANSWER in the same response.
- Arguments must be a valid JSON object.
""".trimIndent()
}
