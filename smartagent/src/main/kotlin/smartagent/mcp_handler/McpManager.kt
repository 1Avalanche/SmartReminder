package smartagent.mcp_handler

/**
 * Central registry for MCP server configurations and active sessions.
 * One session per named server; sessions survive across commands within a CLI run.
 */
object McpManager {

    private val cwd get() = System.getProperty("user.dir")

    // Built-in servers — extend this list or call registerServer() at runtime
    private val builtinServers = listOf(
        McpServerConfig(
            name = "filesystem",
            command = listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", cwd),
            workDir = cwd
        )
    )

    private val extraServers = mutableListOf<McpServerConfig>()
    private val sessions = mutableMapOf<String, McpSession>()

    val allServers: List<McpServerConfig>
        get() = builtinServers + extraServers

    fun getSession(name: String): McpSession? = sessions[name]

    fun isConnected(name: String): Boolean = sessions[name]?.isConnected == true

    /**
     * Starts and initializes a server. If already connected and [force] is false, returns
     * the existing session without restarting the process.
     */
    fun initServer(name: String, force: Boolean = false): McpSession {
        val existing = sessions[name]
        if (existing != null && existing.isConnected && !force) return existing

        existing?.close()
        sessions.remove(name)

        val config = allServers.find { it.name == name }
            ?: throw IllegalArgumentException("Unknown server: \"$name\". Run 'mcp list' to see available servers.")

        val session = McpSession(name, config)
        sessions[name] = session      // register before connect so state is visible
        session.connect()
        return session
    }

    /** Register a custom server that is not in the built-in list. */
    fun registerServer(config: McpServerConfig) {
        extraServers.removeAll { it.name == config.name }
        extraServers.add(config)
    }

    /** Cleanly stop all active sessions (call on CLI exit). */
    fun shutdown() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }
}
