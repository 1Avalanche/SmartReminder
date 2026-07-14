package smartagent.mcp_handler

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpRemoteConfigTest {

    // ─── empty ────────────────────────────────────────────────────────────────

    @Test
    fun `no servers when props and env are empty`() {
        val result = McpRemoteConfig.load(emptyMap(), emptyMap())
        assertTrue(result.isEmpty())
    }

    // ─── MY MCP ───────────────────────────────────────────────────────────────

    @Test
    fun `MY MCP loaded from props with Bearer key`() {
        val result = McpRemoteConfig.load(
            props = mapOf("MCP_SERVER_URL_MY" to "https://my.example.com/mcp", "MCP_API_KEY_MY" to "key-my"),
            env = emptyMap()
        )
        assertEquals(1, result.size)
        assertEquals(RemoteServerEntry(name = "my-mcp", url = "https://my.example.com/mcp", apiKey = "key-my", autoConnect = false), result[0])
    }

    @Test
    fun `MY MCP apiKey null when not set`() {
        val result = McpRemoteConfig.load(
            props = mapOf("MCP_SERVER_URL_MY" to "https://my.example.com/mcp"),
            env = emptyMap()
        )
        assertEquals("my-mcp", result[0].name)
        assertNull(result[0].apiKey)
    }

    @Test
    fun `MY MCP excluded when URL missing`() {
        val result = McpRemoteConfig.load(
            props = mapOf("MCP_API_KEY_MY" to "key-only"),
            env = emptyMap()
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `MY MCP env fallback used when props missing`() {
        val result = McpRemoteConfig.load(
            props = emptyMap(),
            env = mapOf("MCP_SERVER_URL_MY" to "https://env.example.com/mcp", "MCP_API_KEY_MY" to "env-key")
        )
        assertEquals(RemoteServerEntry(name = "my-mcp", url = "https://env.example.com/mcp", apiKey = "env-key", autoConnect = false), result[0])
    }

    @Test
    fun `MY MCP props take precedence over env`() {
        val result = McpRemoteConfig.load(
            props = mapOf("MCP_SERVER_URL_MY" to "https://props.example.com/mcp"),
            env = mapOf("MCP_SERVER_URL_MY" to "https://env.example.com/mcp")
        )
        assertEquals("https://props.example.com/mcp", result[0].url)
    }

    // ─── Tavily MCP ───────────────────────────────────────────────────────────

    @Test
    fun `Tavily URL gets tavilyApiKey query param appended`() {
        val result = McpRemoteConfig.load(
            props = mapOf(
                "MCP_SERVER_URL_TAVILY" to "https://mcp.tavily.com/mcp/",
                "TAVILY_API_KEY" to "tvly-secret"
            ),
            env = emptyMap()
        )
        assertEquals(1, result.size)
        assertEquals("tavily-mcp", result[0].name)
        assertEquals("https://mcp.tavily.com/mcp/?tavilyApiKey=tvly-secret", result[0].url)
        assertNull(result[0].apiKey)  // no Bearer header for Tavily
    }

    @Test
    fun `Tavily URL not duplicated when key already in URL`() {
        val result = McpRemoteConfig.load(
            props = mapOf(
                "MCP_SERVER_URL_TAVILY" to "https://mcp.tavily.com/mcp/?tavilyApiKey=already",
                "TAVILY_API_KEY" to "other"
            ),
            env = emptyMap()
        )
        assertEquals("https://mcp.tavily.com/mcp/?tavilyApiKey=already", result[0].url)
    }

    @Test
    fun `Tavily URL used as-is when no API key configured`() {
        val result = McpRemoteConfig.load(
            props = mapOf("MCP_SERVER_URL_TAVILY" to "https://mcp.tavily.com/mcp/"),
            env = emptyMap()
        )
        assertEquals("https://mcp.tavily.com/mcp/", result[0].url)
    }

    @Test
    fun `Tavily excluded when URL missing even if key present`() {
        val result = McpRemoteConfig.load(
            props = mapOf("TAVILY_API_KEY" to "tvly-secret"),
            env = emptyMap()
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `Tavily apiKey appended with ampersand when URL already has query params`() {
        val result = McpRemoteConfig.load(
            props = mapOf(
                "MCP_SERVER_URL_TAVILY" to "https://mcp.tavily.com/mcp/?version=2",
                "TAVILY_API_KEY" to "tvly-secret"
            ),
            env = emptyMap()
        )
        assertEquals("https://mcp.tavily.com/mcp/?version=2&tavilyApiKey=tvly-secret", result[0].url)
    }

    // ─── combined ─────────────────────────────────────────────────────────────

    @Test
    fun `both servers loaded when both configured`() {
        val result = McpRemoteConfig.load(
            props = mapOf(
                "MCP_SERVER_URL_MY" to "https://my.example.com/mcp",
                "MCP_API_KEY_MY" to "key-my",
                "MCP_SERVER_URL_TAVILY" to "https://mcp.tavily.com/mcp/",
                "TAVILY_API_KEY" to "tvly-secret"
            ),
            env = emptyMap()
        )
        assertEquals(2, result.size)
        assertEquals("my-mcp", result[0].name)
        assertEquals("tavily-mcp", result[1].name)
    }

    @Test
    fun `MY has Bearer apiKey, Tavily has null apiKey`() {
        val result = McpRemoteConfig.load(
            props = mapOf(
                "MCP_SERVER_URL_MY" to "https://my.example.com/mcp",
                "MCP_API_KEY_MY" to "bearer-key",
                "MCP_SERVER_URL_TAVILY" to "https://mcp.tavily.com/mcp/",
                "TAVILY_API_KEY" to "tvly-secret"
            ),
            env = emptyMap()
        )
        assertEquals("bearer-key", result.first { it.name == "my-mcp" }.apiKey)
        assertNull(result.first { it.name == "tavily-mcp" }.apiKey)
    }

    // ─── appendQueryKey ───────────────────────────────────────────────────────

    @Test
    fun `appendQueryKey adds ? when no existing query string`() {
        val result = McpRemoteConfig.appendQueryKey("https://example.com/mcp/", "key", "val")
        assertEquals("https://example.com/mcp/?key=val", result)
    }

    @Test
    fun `appendQueryKey adds & when query string already present`() {
        val result = McpRemoteConfig.appendQueryKey("https://example.com/mcp/?a=1", "key", "val")
        assertEquals("https://example.com/mcp/?a=1&key=val", result)
    }

    @Test
    fun `appendQueryKey skips when param already present`() {
        val url = "https://example.com/mcp/?key=existing"
        val result = McpRemoteConfig.appendQueryKey(url, "key", "new")
        assertEquals(url, result)
    }

    // ─── autoConnect ──────────────────────────────────────────────────────────

    @Test
    fun `my-mcp has autoConnect false`() {
        val result = McpRemoteConfig.load(
            props = mapOf("MCP_SERVER_URL_MY" to "https://my.example.com/mcp"),
            env = emptyMap()
        )
        assertEquals(false, result[0].autoConnect)
    }

    @Test
    fun `tavily-mcp has autoConnect false`() {
        val result = McpRemoteConfig.load(
            props = mapOf("MCP_SERVER_URL_TAVILY" to "https://mcp.tavily.com/mcp/"),
            env = emptyMap()
        )
        assertEquals(false, result[0].autoConnect)
    }
}
