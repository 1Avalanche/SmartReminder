package smartagent.mcp_handler

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import smartagent.NetworkLogger

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
                    ?.removePrefix("GITHUB_HOST=") ?: config.env["GITHUB_HOST"] ?: "<not found>"
                val tokenPresent = config.command.any {
                    it.startsWith("GITHUB_PERSONAL_ACCESS_TOKEN=") && it.length > "GITHUB_PERSONAL_ACCESS_TOKEN=".length
                } || config.env.containsKey("GITHUB_PERSONAL_ACCESS_TOKEN")

                log("=== ${config.name} startup ===")
                log("Command     : $sanitizedCmd")
                log("WorkDir     : ${config.workDir}")
                log("OS          : ${System.getProperty("os.name")} | isWindows=$isWindows")
                log("GITHUB_HOST : $githubHost | token present: $tokenPresent")
                log("PATH        : ${System.getenv("PATH")}")

                val pt = ProcessTransport(config.command, config.workDir, config.env, config.readinessSignal)
                if (config.readinessSignal != null) {
                    val timeoutMs = config.startupDelayMs.coerceAtLeast(60_000)
                    val signalReceived = pt.awaitReady(timeoutMs)
                    log("Readiness signal '${config.readinessSignal}': received=$signalReceived after ${System.currentTimeMillis() - startMs}ms")
                } else if (config.startupDelayMs > 0) {
                    Thread.sleep(config.startupDelayMs)
                }

                val elapsedMs = System.currentTimeMillis() - startMs
                val stderrBeforeInit = pt.drainStderr(0)
                val stdoutBeforeInit = pt.peekStdout()
                log("After startup wait (${elapsedMs}ms): isAlive=${pt.isAlive}")
                if (stdoutBeforeInit.isNotEmpty()) log("stdout before init: ${stdoutBeforeInit.joinToString(" | ")}")
                if (stderrBeforeInit.isNotEmpty()) log("stderr before init: ${stderrBeforeInit.joinToString(" | ")}")

                if (!pt.isAlive) {
                    val extraStderr = pt.drainStderr(1000)
                    val extraStdout = pt.drainStdout(0)
                    val code = pt.exitCode()
                    log("Process dead: exitCode=$code elapsed=${System.currentTimeMillis() - startMs}ms")
                    if (extraStdout.isNotEmpty()) log("stdout (extra): ${extraStdout.joinToString(" | ")}")
                    if (extraStderr.isNotEmpty()) log("stderr (extra): ${extraStderr.joinToString(" | ")}")
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
            log("Before initialize()")
            c.initialize()
            log("After initialize()")
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startMs
            log("initialize() threw ${e.javaClass.simpleName} after ${elapsed}ms: ${e.message}")
            log(e.stackTraceToString())
            val pt = t as? ProcessTransport
            if (pt != null) {
                val stderr = pt.drainStderr(500)
                val stdout = pt.drainStdout(0)
                log("isAlive=${pt.isAlive} exitCode=${pt.exitCode()} elapsed=${elapsed}ms")
                if (stdout.isNotEmpty()) log("stdout: ${stdout.joinToString(" | ")}")
                if (stderr.isNotEmpty()) log("stderr: ${stderr.joinToString(" | ")}")
            }
            throw e
        }

        log("Connected successfully in ${System.currentTimeMillis() - startMs}ms")
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

    private fun log(msg: String) = NetworkLogger.logEvent("[MCP-debug ${config.name}]", msg)
}
