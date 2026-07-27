package smartagent.mcp_handler

enum class TransportMode { PROCESS, HTTP }

data class McpServerConfig(
    val name: String,
    val command: List<String> = emptyList(),
    val workDir: String = System.getProperty("user.dir"),
    val transportMode: TransportMode = TransportMode.PROCESS,
    val httpUrl: String? = null,
    val apiKey: String? = null,
    val env: Map<String, String> = emptyMap(),
    val autoConnect: Boolean = true,
    val startupDelayMs: Long = 2_000,
    /** When set, connect() waits for this substring in server stderr instead of fixed sleep. */
    val readinessSignal: String? = null
)
