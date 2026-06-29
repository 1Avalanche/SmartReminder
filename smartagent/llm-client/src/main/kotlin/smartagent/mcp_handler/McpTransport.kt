package smartagent.mcp_handler

/** Common abstraction over stdio-subprocess and HTTP transports. */
interface McpTransport : AutoCloseable {
    fun send(message: String)
    fun pollLine(timeoutMs: Long): String?
}
