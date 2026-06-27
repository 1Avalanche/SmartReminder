package smartagent.mcp_handler

import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicInteger

data class McpTool(
    val name: String,
    val description: String?,
    val inputSchema: JsonElement? = null
)

class McpClient(private val transport: McpTransport) : AutoCloseable {
    private val nextId = AtomicInteger(1)

    /**
     * MCP handshake — MUST be called before any other request.
     * Sends `initialize`, waits for response, then sends `notifications/initialized`.
     */
    fun initialize() {
        val id = nextId.getAndIncrement()
        val params = buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject {
                put("name", "kotlin-mcp-client")
                put("version", "1.0.0")
            })
        }
        transport.send(JsonRpcSerializer.buildRequest(id, "initialize", params))
        waitForResponse(id) ?: error("initialize timed out — is the server running?")

        // Spec requires this notification before any tool requests
        transport.send(JsonRpcSerializer.buildNotification("notifications/initialized"))
    }

    /** Returns list of tools the server exposes. */
    fun listTools(): List<McpTool> {
        val id = nextId.getAndIncrement()
        transport.send(JsonRpcSerializer.buildRequest(id, "tools/list"))
        val response = waitForResponse(id) ?: return emptyList()

        val tools = response["result"]
            ?.jsonObject?.get("tools")
            ?.jsonArray ?: return emptyList()

        return tools.map { el ->
            val obj = el.jsonObject
            McpTool(
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                description = obj["description"]?.jsonPrimitive?.content,
                inputSchema = obj["inputSchema"]
            )
        }
    }

    /** Calls a tool by name and returns the raw result element. */
    fun callTool(name: String, arguments: Map<String, JsonElement> = emptyMap()): JsonElement? {
        val id = nextId.getAndIncrement()
        val params = buildJsonObject {
            put("name", name)
            put("arguments", buildJsonObject {
                arguments.forEach { (k, v) -> put(k, v) }
            })
        }
        transport.send(JsonRpcSerializer.buildRequest(id, "tools/call", params))
        return waitForResponse(id)?.get("result")
    }

    /**
     * Drains the response queue until a message with [targetId] arrives or timeout expires.
     * Skips server-sent notifications (no `id` field) — they must not be replied to.
     */
    private fun waitForResponse(targetId: Int, timeoutMs: Long = 15_000): JsonObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            val line = transport.pollLine(remaining) ?: return null
            val obj = JsonRpcSerializer.parse(line) ?: continue
            if (JsonRpcSerializer.isNotification(obj)) continue
            val responseId = obj["id"]?.jsonPrimitive?.intOrNull ?: continue
            if (responseId == targetId) return obj
        }
    }

    override fun close() = transport.close()
}
