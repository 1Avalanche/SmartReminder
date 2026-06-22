package smartagent.mcp_handler

import kotlinx.serialization.json.JsonElement

enum class McpConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

class McpSession(
    val name: String,
    val config: McpServerConfig
) : AutoCloseable {

    @Volatile var state: McpConnectionState = McpConnectionState.DISCONNECTED
        private set

    val isConnected: Boolean get() = state == McpConnectionState.CONNECTED

    private var transport: ProcessTransport? = null
    private var client: McpClient? = null

    /**
     * Starts the server process, waits for it to boot, runs the MCP handshake.
     * Idempotent: does nothing if already connected.
     */
    fun connect() {
        if (isConnected) return
        state = McpConnectionState.CONNECTING

        val t = ProcessTransport(config.command, config.workDir)
        Thread.sleep(1_000)   // give npx time to start before we write to stdin

        val c = McpClient(t)
        c.initialize()

        transport = t
        client = c
        state = McpConnectionState.CONNECTED
    }

    fun drainServerOutput(): List<String> = transport?.drainStderr() ?: emptyList()

    fun listTools(): List<McpTool> =
        client?.listTools() ?: emptyList()

    fun callTool(toolName: String, arguments: Map<String, String> = emptyMap()): JsonElement? =
        client?.callTool(toolName, arguments)

    override fun close() {
        state = McpConnectionState.DISCONNECTED
        runCatching { client?.close() }
        client = null
        transport = null
    }
}
