package smartagent.tools.github

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import smartagent.mcp_handler.McpClient
import smartagent.mcp_handler.McpConnectionState
import smartagent.mcp_handler.McpServerConfig
import smartagent.mcp_handler.McpSession
import smartagent.mcp_handler.McpTool
import smartagent.mcp_handler.McpTransport

class FakeMcpSession(
    private val toolResult: JsonElement? = null,
    private val tools: List<McpTool> = emptyList()
) {
    var lastCalledTool: String? = null
    var lastCalledArgs: Map<String, JsonElement> = emptyMap()

    fun asSession(): McpSession {
        val config = McpServerConfig(name = "github", command = emptyList())
        val session = McpSession("github", config)

        val stateField = McpSession::class.java.getDeclaredField("state")
        stateField.isAccessible = true
        stateField.set(session, McpConnectionState.CONNECTED)

        val transport = FakeTransport(this)
        val client = McpClient(transport)
        val clientField = McpSession::class.java.getDeclaredField("client")
        clientField.isAccessible = true
        clientField.set(session, client)

        return session
    }

    fun recordCall(toolName: String, args: Map<String, JsonElement>): JsonElement? {
        lastCalledTool = toolName
        lastCalledArgs = args
        return toolResult
    }

    fun getTools(): List<McpTool> = tools
}

private class FakeTransport(private val fake: FakeMcpSession) : McpTransport {

    private val queue = java.util.concurrent.LinkedBlockingQueue<String>()

    override fun send(request: String) {
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(request) as? JsonObject ?: return
        val method = (parsed["method"] as? JsonPrimitive)?.content ?: return
        val id = (parsed["id"] as? JsonPrimitive)?.content ?: return

        when (method) {
            "initialize" -> queue.put(
                """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2024-11-05","capabilities":{}}}"""
            )
            "tools/list" -> {
                val toolsJson = fake.getTools().joinToString(",") { t ->
                    """{"name":"${t.name}","description":"${t.description ?: ""}"}"""
                }
                queue.put("""{"jsonrpc":"2.0","id":$id,"result":{"tools":[$toolsJson]}}""")
            }
            "tools/call" -> {
                val params = parsed["params"] as? JsonObject
                val toolName = (params?.get("name") as? JsonPrimitive)?.content ?: "unknown"
                val argsEl = params?.get("arguments") as? JsonObject ?: JsonObject(emptyMap())
                val result = fake.recordCall(toolName, argsEl)
                if (result != null) {
                    val resultStr = kotlinx.serialization.json.Json.encodeToString(JsonElement.serializer(), result)
                    queue.put("""{"jsonrpc":"2.0","id":$id,"result":$resultStr}""")
                } else {
                    // Empty content array — renderToolResult returns ""
                    queue.put("""{"jsonrpc":"2.0","id":$id,"result":{"content":[],"isError":false}}""")
                }
            }
            else -> queue.put("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
        }
    }

    override fun pollLine(timeoutMs: Long): String? = queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    override fun close() {}
}
