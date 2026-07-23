package smartagent.mcp_handler

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

enum class McpConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

open class McpSession(
    val name: String,
    val config: McpServerConfig
) : AutoCloseable {

    @Volatile var state: McpConnectionState = McpConnectionState.DISCONNECTED
        private set

    open val isConnected: Boolean get() = state == McpConnectionState.CONNECTED

    private var transport: McpTransport? = null
    private var client: McpClient? = null

    /**
     * Starts the server (process or HTTP), runs the MCP handshake.
     * Idempotent: does nothing if already connected.
     */
    fun connect() {
        if (isConnected) return
        state = McpConnectionState.CONNECTING

        val t: McpTransport = when (config.transportMode) {
            TransportMode.PROCESS -> {
                println("[MCP] Starting ${config.name} (first run may download package)...")
                val pt = ProcessTransport(config.command, config.workDir, config.env)
                if (config.startupDelayMs > 0) Thread.sleep(config.startupDelayMs)
                if (!pt.isAlive) {
                    val stderr = pt.drainStderr(0)
                    error("Server process exited on startup. stderr: ${stderr.joinToString("; ").ifBlank { "<none>" }}")
                }
                pt
            }
            TransportMode.HTTP -> McpHttpTransport(
                serverUrl = config.httpUrl ?: error("httpUrl required for HTTP transport"),
                apiKey = config.apiKey
            )
        }

        transport = t
        val c = McpClient(t)
        c.initialize()

        client = c
        state = McpConnectionState.CONNECTED
    }

    /** For process servers: drains stderr lines printed during startup. HTTP servers return empty. */
    fun drainServerOutput(): List<String> =
        (transport as? ProcessTransport)?.drainStderr() ?: emptyList()

    open fun listTools(): List<McpTool> =
        client?.listTools() ?: emptyList()

    open fun callTool(toolName: String, arguments: Map<String, JsonElement> = emptyMap()): JsonElement? =
        client?.callTool(toolName, arguments)

    override fun close() {
        state = McpConnectionState.DISCONNECTED
        runCatching { client?.close() }
        client = null
        transport = null
    }
}
