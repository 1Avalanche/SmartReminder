package smartagent.mcp_handler

import smartagent.Colors
import smartagent.Config

/**
 * Central registry for MCP server configurations and active sessions.
 * One session per named server; sessions survive across commands within a CLI run.
 */
object McpManager {

    private val cwd get() = System.getProperty("user.dir")

    private val builtinServers: List<McpServerConfig> = buildBuiltinServers(McpRemoteConfig.servers)

    private val extraServers = mutableListOf<McpServerConfig>()
    private val sessions = mutableMapOf<String, McpSession>()

    val allServers: List<McpServerConfig>
        get() = builtinServers + extraServers

    fun getSession(name: String): McpSession? = sessions[name]

    fun isConnected(name: String): Boolean = sessions[name]?.isConnected == true

    /**
     * Starts and initializes a server. If already connected and [force] is false, returns
     * the existing session without restarting.
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

    /**
     * Connects to all autoConnect servers in parallel, logging per-server results with tool names.
     * Each server runs in its own daemon thread; all threads are joined before returning.
     * A single server failure does not block others.
     */
    fun initRemoteServers() {
        val autoConnectServers = builtinServers.filter { it.autoConnect }
        if (autoConnectServers.isEmpty()) {
            println("${Colors.DARK_GRAY}[MCP] No servers configured for auto-connect.${Colors.RESET}")
            return
        }
        val threads = autoConnectServers.map { cfg ->
            Thread {
                try {
                    val session = initServer(cfg.name)
                    val tools = session.listTools()
                    println("${Colors.LIGHT_GREEN}[MCP] ${cfg.name}: ${tools.size} tools${Colors.RESET}")
                    tools.forEach { tool ->
                        println("${Colors.DARK_GRAY}  · ${tool.name}${Colors.RESET}")
                    }
                } catch (e: Exception) {
                    println("${Colors.LIGHT_YELLOW}[MCP] Failed to connect to ${cfg.name}: ${e.message}${Colors.RESET}")
                    sessions[cfg.name]?.drainServerOutput()?.forEach { line ->
                        println("${Colors.DARK_GRAY}  [stderr] $line${Colors.RESET}")
                    }
                }
            }.also { it.isDaemon = true; it.start() }
        }
        threads.forEach { it.join() }
    }

    /** Register a custom server that is not in the built-in list. */
    fun registerServer(config: McpServerConfig) {
        extraServers.removeAll { it.name == config.name }
        extraServers.add(config)
    }

    /** Register a pre-built session (e.g. in-process MCP) without spawning a transport. */
    fun registerSession(name: String, session: McpSession) {
        if (extraServers.none { it.name == name }) {
            extraServers.add(McpServerConfig(name = name, command = emptyList(), autoConnect = false))
        }
        sessions[name] = session
    }

    /** Cleanly stop all active sessions (call on CLI exit). */
    fun shutdown() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    internal fun buildBuiltinServers(remoteEntries: List<RemoteServerEntry>): List<McpServerConfig> = buildList {
        add(
            McpServerConfig(
                name = "filesystem",
                command = listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", cwd),
                workDir = cwd,
                autoConnect = false
            )
        )
        val githubToken = Config.localProperties["GITHUB_CORP_TOKEN"]
            ?: System.getenv("GITHUB_CORP_TOKEN")
        val githubHost = Config.localProperties["GITHUB_CORP_HOST"]
            ?: System.getenv("GITHUB_CORP_HOST")
        if (githubToken != null) {
            val resolvedHost = githubHost ?: "https://github.lmru.tech"
            add(
                McpServerConfig(
                    name = "github",
                    command = listOf(
                        "docker", "run", "-i", "--rm",
                        "-e", "GITHUB_PERSONAL_ACCESS_TOKEN=$githubToken",
                        "-e", "GITHUB_HOST=$resolvedHost",
                        "ghcr.io/github/github-mcp-server"
                    ),
                    workDir = cwd,
                    autoConnect = true
                )
            )
        }
        remoteEntries.forEach { entry ->
            add(
                McpServerConfig(
                    name = entry.name,
                    transportMode = TransportMode.HTTP,
                    httpUrl = entry.url,
                    apiKey = entry.apiKey,
                    autoConnect = entry.autoConnect
                )
            )
        }
    }
}
