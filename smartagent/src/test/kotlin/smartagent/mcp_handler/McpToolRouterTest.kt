package smartagent.mcp_handler

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParseToolArgsTest {

    @Test
    fun `single key=value`() {
        val result = parseToolArgs(listOf("owner=octocat"))
        assertEquals(mapOf("owner" to "octocat"), result)
    }

    @Test
    fun `multiple key=value pairs`() {
        val result = parseToolArgs(listOf("owner=octocat", "repo=Hello-World", "state=open"))
        assertEquals(mapOf("owner" to "octocat", "repo" to "Hello-World", "state" to "open"), result)
    }

    @Test
    fun `value containing = is preserved`() {
        val result = parseToolArgs(listOf("filter=a=b"))
        assertEquals(mapOf("filter" to "a=b"), result)
    }

    @Test
    fun `tokens without = are skipped`() {
        val result = parseToolArgs(listOf("noequals", "owner=octocat"))
        assertEquals(mapOf("owner" to "octocat"), result)
    }

    @Test
    fun `empty list returns empty map`() {
        assertTrue(parseToolArgs(emptyList()).isEmpty())
    }

    @Test
    fun `token starting with = is skipped (empty key)`() {
        val result = parseToolArgs(listOf("=value"))
        assertTrue(result.isEmpty())
    }
}

class McpToolRouterTest {

    @Test
    fun `returns Error when server not connected`() {
        val result = McpToolRouter.callTool("nonexistent-server", "some_tool", emptyMap())
        assertIs<ToolCallResult.Error>(result)
        assertTrue(result.message.contains("not connected"))
    }

    @Test
    fun `returns Error message includes server name`() {
        val result = McpToolRouter.callTool("my-server", "tool_x", emptyMap())
        assertIs<ToolCallResult.Error>(result)
        assertTrue(result.message.contains("my-server"))
    }

    @Test
    fun `routes through session when connected`() {
        val fakeTransport = FakeMcpTransport(toolCallResultBody = buildJsonObject { put("data", "ok") })
        val config = McpServerConfig(name = "fake-server")
        val session = makeConnectedSession("fake-server", config, fakeTransport)
        McpManager.registerServer(config)
        injectSession("fake-server", session)

        val result = McpToolRouter.callTool("fake-server", "some_tool", mapOf("k" to "v"))
        assertIs<ToolCallResult.Success>(result)
    }

    @Test
    fun `returns Error when tool call returns null`() {
        val fakeTransport = FakeMcpTransport(toolCallResultBody = null)
        val config = McpServerConfig(name = "null-server")
        val session = makeConnectedSession("null-server", config, fakeTransport)
        McpManager.registerServer(config)
        injectSession("null-server", session)

        val result = McpToolRouter.callTool("null-server", "missing_tool", emptyMap())
        assertIs<ToolCallResult.Error>(result)
        assertTrue(result.message.contains("no result"))
    }

    // ─── Test helpers ─────────────────────────────────────────────────────────

    private fun injectSession(name: String, session: McpSession) {
        val sessionsField = McpManager::class.java.getDeclaredField("sessions")
        sessionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sessions = sessionsField.get(McpManager) as MutableMap<String, McpSession>
        sessions[name] = session
    }

    private fun makeConnectedSession(name: String, config: McpServerConfig, transport: FakeMcpTransport): McpSession {
        val session = McpSession(name, config)
        val stateField = McpSession::class.java.getDeclaredField("state")
        stateField.isAccessible = true
        stateField.set(session, McpConnectionState.CONNECTED)
        val client = McpClient(transport)
        val clientField = McpSession::class.java.getDeclaredField("client")
        clientField.isAccessible = true
        clientField.set(session, client)
        return session
    }
}

/**
 * Transport that echoes the request id back in responses.
 * [toolCallResultBody] is the `result` value for tools/call — null simulates timeout.
 */
private class FakeMcpTransport(
    private val toolCallResultBody: kotlinx.serialization.json.JsonObject?
) : McpTransport {

    private val queue = java.util.concurrent.LinkedBlockingQueue<String>()

    override fun send(request: String) {
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(request)
            .let { it as? kotlinx.serialization.json.JsonObject } ?: return
        val method = parsed["method"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: return
        // notifications have no id — skip
        val id = parsed["id"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: return

        when (method) {
            "tools/call" -> toolCallResultBody?.let {
                queue.put("""{"jsonrpc":"2.0","id":$id,"result":${it}}""")
            }
            "initialize" -> queue.put(
                """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2024-11-05","capabilities":{}}}"""
            )
        }
    }

    override fun pollLine(timeoutMs: Long): String? =
        queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    override fun close() {}
}
