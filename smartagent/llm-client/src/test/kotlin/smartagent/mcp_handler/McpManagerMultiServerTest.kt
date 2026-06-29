package smartagent.mcp_handler

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpManagerMultiServerTest {

    @Test
    fun `filesystem server always present`() {
        val servers = McpManager.buildBuiltinServers(emptyList())
        assertNotNull(servers.find { it.name == "filesystem" })
    }

    @Test
    fun `filesystem server uses PROCESS transport`() {
        val servers = McpManager.buildBuiltinServers(emptyList())
        val fs = servers.first { it.name == "filesystem" }
        assertEquals(TransportMode.PROCESS, fs.transportMode)
    }

    @Test
    fun `remote entries added after filesystem`() {
        val entries = listOf(
            RemoteServerEntry(name = "my-mcp", url = "https://my.example.com/mcp", apiKey = "k1"),
            RemoteServerEntry(name = "tavily-mcp", url = "https://mcp.tavily.com/mcp/?tavilyApiKey=x", apiKey = null)
        )
        val servers = McpManager.buildBuiltinServers(entries)
        assertEquals(3, servers.size)
        assertEquals("filesystem", servers[0].name)
        assertEquals("my-mcp", servers[1].name)
        assertEquals("tavily-mcp", servers[2].name)
    }

    @Test
    fun `MY MCP carries Bearer apiKey`() {
        val entry = RemoteServerEntry(name = "my-mcp", url = "https://my.example.com/mcp", apiKey = "secret")
        val servers = McpManager.buildBuiltinServers(listOf(entry))
        val remote = servers.first { it.name == "my-mcp" }
        assertEquals(TransportMode.HTTP, remote.transportMode)
        assertEquals("https://my.example.com/mcp", remote.httpUrl)
        assertEquals("secret", remote.apiKey)
    }

    @Test
    fun `Tavily MCP has null apiKey and URL-embedded key`() {
        val tavilyUrl = "https://mcp.tavily.com/mcp/?tavilyApiKey=tvly-secret"
        val entry = RemoteServerEntry(name = "tavily-mcp", url = tavilyUrl, apiKey = null)
        val servers = McpManager.buildBuiltinServers(listOf(entry))
        val remote = servers.first { it.name == "tavily-mcp" }
        assertEquals(TransportMode.HTTP, remote.transportMode)
        assertEquals(tavilyUrl, remote.httpUrl)
        assertNull(remote.apiKey)
    }

    @Test
    fun `single remote entry produces filesystem + one remote`() {
        val servers = McpManager.buildBuiltinServers(
            listOf(RemoteServerEntry(name = "my-mcp", url = "https://my.example.com/mcp", apiKey = "k"))
        )
        assertEquals(2, servers.size)
        assertTrue(servers.any { it.name == "filesystem" })
        assertTrue(servers.any { it.name == "my-mcp" })
    }

    @Test
    fun `no remote entries produces only filesystem`() {
        val servers = McpManager.buildBuiltinServers(emptyList())
        assertEquals(1, servers.size)
        assertEquals("filesystem", servers[0].name)
    }
}
