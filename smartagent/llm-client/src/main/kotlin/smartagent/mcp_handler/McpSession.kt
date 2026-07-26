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

        val startMs = System.currentTimeMillis()

        val t: McpTransport = when (config.transportMode) {
            TransportMode.PROCESS -> {
                val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
                val sanitizedCmd = config.command.joinToString(" ") { arg ->
                    if (arg.startsWith("GITHUB_PERSONAL_ACCESS_TOKEN=")) "GITHUB_PERSONAL_ACCESS_TOKEN=<redacted>" else arg
                }
                val githubHost = config.command.firstOrNull { it.startsWith("GITHUB_HOST=") }
                    ?.removePrefix("GITHUB_HOST=") ?: "<not found in command>"
                val tokenPresent = config.command.any {
                    it.startsWith("GITHUB_PERSONAL_ACCESS_TOKEN=") && it.length > "GITHUB_PERSONAL_ACCESS_TOKEN=".length
                }

                println("[MCP-debug] === ${config.name} startup ===")
                println("[MCP-debug] Command     : $sanitizedCmd")
                println("[MCP-debug] WorkDir     : ${config.workDir}")
                println("[MCP-debug] OS          : ${System.getProperty("os.name")} | isWindows=$isWindows")
                println("[MCP-debug] GITHUB_HOST : $githubHost | token present: $tokenPresent")
                println("[MCP-debug] PATH        : ${System.getenv("PATH")}")

                println("[MCP] Starting ${config.name} (first run may download package)...")

                val pt = ProcessTransport(config.command, config.workDir, config.env)
                if (config.startupDelayMs > 0) Thread.sleep(config.startupDelayMs)

                val elapsedMs = System.currentTimeMillis() - startMs
                val stderrBeforeInit = pt.drainStderr(0)
                val stdoutBeforeInit = pt.peekStdout()
                println("[MCP-debug] After delay (${elapsedMs}ms): isAlive=${pt.isAlive}")
                if (stdoutBeforeInit.isNotEmpty())
                    println("[MCP-debug] stdout before init: ${stdoutBeforeInit.joinToString(" | ")}")
                if (stderrBeforeInit.isNotEmpty())
                    println("[MCP-debug] stderr before init: ${stderrBeforeInit.joinToString(" | ")}")

                if (!pt.isAlive) {
                    val extraStderr = pt.drainStderr(1000)
                    val extraStdout = pt.drainStdout(0)
                    val code = pt.exitCode()
                    println("[MCP-debug] Process dead: exitCode=$code elapsed=${System.currentTimeMillis() - startMs}ms")
                    if (extraStdout.isNotEmpty()) println("[MCP-debug] stdout (extra): ${extraStdout.joinToString(" | ")}")
                    if (extraStderr.isNotEmpty()) println("[MCP-debug] stderr (extra): ${extraStderr.joinToString(" | ")}")
                    val allStderr = stderrBeforeInit + extraStderr
                    error("Server process exited on startup (exit code: $code). stderr: ${allStderr.joinToString("; ").ifBlank { "<none>" }}")
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
        try {
            c.initialize()
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startMs
            println("[MCP-debug] initialize() threw ${e.javaClass.simpleName} after ${elapsed}ms: ${e.message}")
            e.printStackTrace(System.out)
            val pt = t as? ProcessTransport
            if (pt != null) {
                val stderr = pt.drainStderr(500)
                val stdout = pt.drainStdout(0)
                println("[MCP-debug] isAlive=${pt.isAlive} exitCode=${pt.exitCode()} elapsed=${elapsed}ms")
                if (stdout.isNotEmpty()) println("[MCP-debug] stdout: ${stdout.joinToString(" | ")}")
                if (stderr.isNotEmpty()) println("[MCP-debug] stderr: ${stderr.joinToString(" | ")}")
            }
            throw e
        }

        println("[MCP-debug] Connected successfully in ${System.currentTimeMillis() - startMs}ms")
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
