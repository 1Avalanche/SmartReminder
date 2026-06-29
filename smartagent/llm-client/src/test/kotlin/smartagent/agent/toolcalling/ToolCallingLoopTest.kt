package smartagent.agent.toolcalling

import kotlinx.serialization.json.JsonElement
import smartagent.FakeLLMGateway
import smartagent.ModelConfig
import smartagent.mcp_handler.McpConnectionState
import smartagent.mcp_handler.McpServerConfig
import smartagent.mcp_handler.McpSession
import smartagent.mcp_handler.McpTool
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolCallingLoopTest {

    private val model = ModelConfig.DEEPSEEK

    // ─── helpers ──────────────────────────────────────────────────────────────

    private fun makeLoop(
        gateway: FakeLLMGateway,
        fakeSession: FakeSession,
        maxIterations: Int = 5
    ) = ToolCallingLoop(
        sessions = mapOf("test-server" to fakeSession.asSession()),
        gateway = gateway,
        model = model,
        maxIterations = maxIterations
    )

    // ─── tests ────────────────────────────────────────────────────────────────

    @Test
    fun `LLM returns FINAL_ANSWER immediately — 1 call`() {
        val gateway = FakeLLMGateway("FINAL_ANSWER\nHere is the answer.")
        val fakeSession = FakeSession()
        val loop = makeLoop(gateway, fakeSession)

        val result = loop.run("what repos exist?")

        assertEquals("Here is the answer.", result)
        assertEquals(1, gateway.callCount)
    }

    @Test
    fun `LLM calls one tool then returns FINAL_ANSWER`() {
        val gateway = FakeLLMGateway(
            "TOOL_CALL\ntool=search_repos\narguments={\"query\":\"kotlin\"}",
            "FINAL_ANSWER\nFound 3 repositories."
        )
        val fakeSession = FakeSession(toolResult = "repo1, repo2, repo3", toolNames = listOf("search_repos"))
        val loop = makeLoop(gateway, fakeSession)

        val result = loop.run("find kotlin repos")

        assertEquals("Found 3 repositories.", result)
        assertEquals(2, gateway.callCount)
        assertEquals(1, fakeSession.callCount)
        assertEquals("search_repos", fakeSession.lastToolName)
    }

    @Test
    fun `tool result is injected into next LLM call`() {
        val gateway = FakeLLMGateway(
            "TOOL_CALL\ntool=my_tool\narguments={}",
            "FINAL_ANSWER\nDone."
        )
        val fakeSession = FakeSession(toolResult = "result data", toolNames = listOf("my_tool"))
        val loop = makeLoop(gateway, fakeSession)

        loop.run("query")

        val secondCallMessages = gateway.calls[1].first
        val userMessage = secondCallMessages.last()
        assertEquals("user", userMessage.role)
        assertTrue(userMessage.content.contains("my_tool"))
        assertTrue(userMessage.content.contains("result data"))
    }

    @Test
    fun `tool execution error is fed back to LLM`() {
        val gateway = FakeLLMGateway(
            "TOOL_CALL\ntool=broken_tool\narguments={}",
            "FINAL_ANSWER\nSorry, could not fetch."
        )
        val fakeSession = FakeSession(toolThrows = RuntimeException("connection refused"), toolNames = listOf("broken_tool"))
        val loop = makeLoop(gateway, fakeSession)

        val result = loop.run("query")

        assertEquals("Sorry, could not fetch.", result)
        val secondCallMessages = gateway.calls[1].first
        val userMessage = secondCallMessages.last()
        assertTrue(userMessage.content.contains("error") || userMessage.content.contains("connection refused"))
    }

    @Test
    fun `maxIterations exceeded returns user-friendly message`() {
        // LLM always returns TOOL_CALL — never FINAL_ANSWER
        val responses = Array(10) { "TOOL_CALL\ntool=search\narguments={}" }
        val gateway = FakeLLMGateway(*responses)
        val fakeSession = FakeSession(toolResult = "data")
        val loop = makeLoop(gateway, fakeSession, maxIterations = 3)

        val result = loop.run("query")

        assertTrue(OutputValidator.isSafeForUser(result))
        assertTrue(result.isNotBlank())
        assertEquals(3, gateway.callCount)
    }

    @Test
    fun `ParseError with long natural language text returns it directly`() {
        val naturalText = "По запросу ничего не нашлось. Возможно, в названии ошибка. Уточните, пожалуйста, домен сайта."
        val gateway = FakeLLMGateway(naturalText)
        val fakeSession = FakeSession()
        val loop = makeLoop(gateway, fakeSession)

        val result = loop.run("find modiledeveloper")

        assertEquals(naturalText, result)
        assertEquals(1, gateway.callCount)
    }

    @Test
    fun `ParseError with short text still triggers recovery prompt`() {
        val shortText = "Уточните."  // < 60 chars
        val gateway = FakeLLMGateway(shortText, "FINAL_ANSWER\nОК.")
        val fakeSession = FakeSession()
        val loop = makeLoop(gateway, fakeSession)

        val result = loop.run("query")

        assertEquals("ОК.", result)
        assertEquals(2, gateway.callCount)
    }

    @Test
    fun `ParseError with TOOL_CALL fragment still triggers recovery prompt`() {
        val malformed = "I want to TOOL_CALL search but I forgot the format and wrote this long paragraph here."
        val gateway = FakeLLMGateway(malformed, "FINAL_ANSWER\nDone.")
        val fakeSession = FakeSession()
        val loop = makeLoop(gateway, fakeSession)

        val result = loop.run("query")

        assertEquals("Done.", result)
        assertEquals(2, gateway.callCount)
    }

    @Test
    fun `tavily_extract with list arg preserves array type in MCP payload`() {
        // LLM outputs urls as a JSON array — must arrive at MCP as array, not string
        val gateway = FakeLLMGateway(
            """TOOL_CALL
tool=tavily_extract
arguments={"urls":["https://example.com"]}""",
            "FINAL_ANSWER\nExtracted."
        )
        val fakeSession = FakeSession(
            toolResult = "page content",
            toolNames = listOf("tavily_extract")
        )
        fakeSession.expectArrayArg = true
        val loop = makeLoop(gateway, fakeSession)

        val result = loop.run("extract https://example.com")

        assertEquals("Extracted.", result)
        assertEquals(1, fakeSession.callCount)
        assertTrue(fakeSession.lastArgsWereArray, "urls arg must be JsonArray, not String")
    }

    @Test
    fun `LLM calls multiple tools sequentially`() {
        val gateway = FakeLLMGateway(
            "TOOL_CALL\ntool=tool_a\narguments={}",
            "TOOL_CALL\ntool=tool_b\narguments={}",
            "FINAL_ANSWER\nBoth done."
        )
        val fakeSession = FakeSession(toolResult = "ok", toolNames = listOf("tool_a", "tool_b"))
        val loop = makeLoop(gateway, fakeSession)

        val result = loop.run("do both")

        assertEquals("Both done.", result)
        assertEquals(3, gateway.callCount)
        assertEquals(2, fakeSession.callCount)
    }
}

// ─── Fake session ──────────────────────────────────────────────────────────────

class FakeSession(
    private val toolResult: String? = null,
    private val toolThrows: Exception? = null,
    private val tools: List<McpTool> = emptyList(),
    private val toolNames: List<String> = emptyList()
) {
    var callCount = 0
    var lastToolName: String? = null
    var expectArrayArg: Boolean = false
    var lastArgsWereArray: Boolean = false

    fun asSession(): McpSession {
        val config = McpServerConfig(name = "test-server")
        val session = McpSession("test-server", config)

        // Force CONNECTED state
        val stateField = McpSession::class.java.getDeclaredField("state")
        stateField.isAccessible = true
        stateField.set(session, McpConnectionState.CONNECTED)

        // Inject a fake client that delegates to this FakeSession
        val fakeTransport = FakeSessionTransport(this)
        val client = smartagent.mcp_handler.McpClient(fakeTransport)
        val clientField = McpSession::class.java.getDeclaredField("client")
        clientField.isAccessible = true
        clientField.set(session, client)

        return session
    }

    fun executeCall(toolName: String): JsonElement? {
        callCount++
        lastToolName = toolName
        if (toolThrows != null) throw toolThrows
        if (toolResult == null) return null
        return kotlinx.serialization.json.buildJsonObject {
            kotlinx.serialization.json.JsonObject(mapOf("text" to kotlinx.serialization.json.JsonPrimitive(toolResult)))
                .let { obj -> obj.entries.forEach { (k, v) -> put(k, v) } }
        }
    }

    fun getTools(): List<McpTool> = tools + toolNames.map { McpTool(it, null, null) }
}

/**
 * Transport that intercepts tools/call and tools/list JSON-RPC requests
 * and delegates to FakeSession.
 */
private class FakeSessionTransport(private val fake: FakeSession) : smartagent.mcp_handler.McpTransport {

    private val queue = java.util.concurrent.LinkedBlockingQueue<String>()

    override fun send(request: String) {
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(request)
            .let { it as? kotlinx.serialization.json.JsonObject } ?: return
        val method = parsed["method"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: return
        val id = parsed["id"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: return

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
                val params = parsed["params"] as? kotlinx.serialization.json.JsonObject
                val toolName = (params?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "unknown"
                if (fake.expectArrayArg) {
                    val argsObj = params?.get("arguments") as? kotlinx.serialization.json.JsonObject
                    val urlsArg = argsObj?.get("urls")
                    fake.lastArgsWereArray = urlsArg is kotlinx.serialization.json.JsonArray
                }
                try {
                    val result = fake.executeCall(toolName)
                    val resultJson = result?.toString() ?: "null"
                    queue.put("""{"jsonrpc":"2.0","id":$id,"result":$resultJson}""")
                } catch (e: Exception) {
                    throw e  // propagates through McpClient.callTool
                }
            }
        }
    }

    override fun pollLine(timeoutMs: Long): String? =
        queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    override fun close() {}
}
