package smartagent.mcp_handler

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class McpHttpTransportTest {

    private lateinit var server: MockWebServer
    private lateinit var transport: McpHttpTransport

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        transport = McpHttpTransport(serverUrl = server.url("/mcp").toString())
    }

    @After
    fun tearDown() {
        transport.close()
        server.shutdown()
    }

    // ─── initialize ───────────────────────────────────────────────────────────

    @Test
    fun `initialize response is enqueued and parseable`() {
        val initResponse = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("result", buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", buildJsonObject {})
            })
        }.toString()
        server.enqueue(MockResponse().setBody(initResponse).setResponseCode(200))

        val request = JsonRpcSerializer.buildRequest(1, "initialize")
        transport.send(request)

        val line = transport.pollLine(3_000)
        assertNotNull(line)
        val parsed = Json.parseToJsonElement(line).jsonObject
        assertEquals("2.0", parsed["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals(1, parsed["id"]?.jsonPrimitive?.content?.toIntOrNull())
    }

    @Test
    fun `notification is fire-and-forget — nothing enqueued`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val notification = JsonRpcSerializer.buildNotification("notifications/initialized")
        transport.send(notification)

        val line = transport.pollLine(200)
        assertNull(line, "notification must not enqueue a response")
    }

    // ─── tools/list ───────────────────────────────────────────────────────────

    @Test
    fun `tools list response is parsed correctly by McpClient`() {
        val toolsResponse = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("result", buildJsonObject {
                put("tools", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("name", "listRepositories")
                        put("description", "List GitHub repositories")
                    })
                    add(buildJsonObject {
                        put("name", "getIssues")
                        put("description", "Get issues for a repo")
                    })
                })
            })
        }.toString()
        server.enqueue(MockResponse().setBody(toolsResponse).setResponseCode(200))

        transport.send(JsonRpcSerializer.buildRequest(1, "tools/list"))
        val line = transport.pollLine(3_000)
        assertNotNull(line)

        val tools = Json.parseToJsonElement(line)
            .jsonObject["result"]
            ?.jsonObject?.get("tools")
            ?.jsonArray
        assertNotNull(tools)
        assertEquals(2, tools.size)
        assertEquals("listRepositories", tools[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("getIssues", tools[1].jsonObject["name"]?.jsonPrimitive?.content)
    }

    // ─── tools/call ───────────────────────────────────────────────────────────

    @Test
    fun `tools call success response is enqueued`() {
        val callResponse = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("result", buildJsonObject {
                put("content", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "[{\"name\":\"my-repo\"}]")
                    })
                })
            })
        }.toString()
        server.enqueue(MockResponse().setBody(callResponse).setResponseCode(200))

        transport.send(JsonRpcSerializer.buildRequest(1, "tools/call"))
        val line = transport.pollLine(3_000)
        assertNotNull(line)

        val result = Json.parseToJsonElement(line).jsonObject["result"]?.jsonObject
        assertNotNull(result)
        assertNotNull(result["content"])
    }

    @Test
    fun `tools call error response is enqueued and contains error field`() {
        val errorResponse = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("error", buildJsonObject {
                put("code", -32601)
                put("message", "Method not found")
            })
        }.toString()
        server.enqueue(MockResponse().setBody(errorResponse).setResponseCode(200))

        transport.send(JsonRpcSerializer.buildRequest(1, "tools/call"))
        val line = transport.pollLine(3_000)
        assertNotNull(line)

        val parsed = Json.parseToJsonElement(line).jsonObject
        assertNotNull(parsed["error"], "error field must be present")
        assertEquals("Method not found", parsed["error"]!!.jsonObject["message"]?.jsonPrimitive?.content)
    }

    // ─── Authorization header ─────────────────────────────────────────────────

    @Test
    fun `api key is sent as Bearer token`() {
        val apiKeyTransport = McpHttpTransport(
            serverUrl = server.url("/mcp").toString(),
            apiKey = "test-secret-key"
        )
        try {
            server.enqueue(MockResponse().setBody(
                JsonRpcSerializer.buildRequest(1, "initialize") // reuse as dummy response body
            ).setResponseCode(200))

            apiKeyTransport.send(JsonRpcSerializer.buildRequest(1, "initialize"))

            val recorded = server.takeRequest()
            assertEquals("Bearer test-secret-key", recorded.getHeader("Authorization"))
        } finally {
            apiKeyTransport.close()
        }
    }

    @Test
    fun `no Authorization header when api key is null`() {
        server.enqueue(MockResponse().setBody(
            buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("result", buildJsonObject {}) }.toString()
        ).setResponseCode(200))

        transport.send(JsonRpcSerializer.buildRequest(1, "initialize"))
        server.takeRequest().also { req ->
            assertNull(req.getHeader("Authorization"), "Authorization must be absent when no api key")
        }
    }
}
