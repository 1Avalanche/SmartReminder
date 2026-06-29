package smartagent.mcp_handler

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

sealed class ToolCallResult {
    data class Success(val result: JsonElement) : ToolCallResult()
    data class Error(val message: String) : ToolCallResult()
}

object McpToolRouter {

    fun callTool(serverName: String, toolName: String, args: Map<String, String>): ToolCallResult {
        val session = McpManager.getSession(serverName)
        if (session == null || !session.isConnected) {
            return ToolCallResult.Error("$serverName not connected. Run: /mcp $serverName init")
        }
        return try {
            val result = session.callTool(toolName, args.mapValues { JsonPrimitive(it.value) })
                ?: return ToolCallResult.Error("Tool call returned no result (timeout or unknown tool \"$toolName\")")
            ToolCallResult.Success(result)
        } catch (e: Exception) {
            ToolCallResult.Error(e.message ?: "Unknown error")
        }
    }
}

/** Parses key=value token list into a Map. Tokens without '=' are silently skipped. */
fun parseToolArgs(tokens: List<String>): Map<String, String> =
    tokens.mapNotNull { token ->
        val idx = token.indexOf('=')
        if (idx <= 0) null else token.substring(0, idx) to token.substring(idx + 1)
    }.toMap()
