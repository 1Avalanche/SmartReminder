package smartagent.mcp_handler

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ProcessTransport(
    command: List<String>,
    workDir: String,
    env: Map<String, String> = emptyMap(),
    readinessSignal: String? = null
) : McpTransport {
    private val process: Process
    private val writer: BufferedWriter
    private val responseQueue = LinkedBlockingQueue<String>()
    private val stderrQueue = LinkedBlockingQueue<String>()
    private val readinessLatch = if (readinessSignal != null) CountDownLatch(1) else null

    init {
        process = ProcessBuilder(command)
            .directory(File(workDir))
            .also { pb -> if (env.isNotEmpty()) pb.environment().putAll(env) }
            .start()

        writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))

        // Reads server stdout in background; each newline-delimited JSON line → queue
        thread("mcp-stdout") {
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        smartagent.NetworkLogger.logEvent("[MCP-transport]", "<<< $line")
                        if (line.isNotBlank()) responseQueue.put(line)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) { /* stream closed */ }
            }
            smartagent.NetworkLogger.logEvent("[MCP-transport]", "stdout stream closed")
        }

        // Buffer server stderr; also stream to NetworkLogger for live diagnostics
        thread("mcp-stderr") {
            BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use { reader ->
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        stderrQueue.put(line)
                        smartagent.NetworkLogger.logEvent("[MCP-stderr]", line)
                        if (readinessSignal != null && line.contains(readinessSignal)) {
                            readinessLatch?.countDown()
                        }
                    }
                } catch (e: Exception) { /* stream closed */ }
            }
        }
    }

    /**
     * Blocks until the readiness signal line appears in stderr, or [timeoutMs] elapses.
     * Returns true if signal received, false on timeout or if no signal was configured.
     */
    fun awaitReady(timeoutMs: Long): Boolean =
        readinessLatch?.await(timeoutMs, TimeUnit.MILLISECONDS) ?: false

    override fun send(message: String) {
        smartagent.NetworkLogger.logEvent("[MCP-transport]", ">>> $message")
        writer.write(message)
        writer.write("\n")  // always LF — MCP server runs in Linux container, \r\n breaks protocol
        writer.flush()
    }

    /** Blocks up to [timeoutMs] for next line from server stdout. Returns null on timeout. */
    override fun pollLine(timeoutMs: Long): String? {
        val line = responseQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (line == null) smartagent.NetworkLogger.logEvent("[MCP-transport]", "pollLine timeout after ${timeoutMs}ms")
        return line
    }

    /**
     * Waits [waitMs] for any late-arriving stderr lines, then returns and clears the buffer.
     * Call this synchronously from the main thread after connect() to avoid interleaving with the REPL prompt.
     */
    fun drainStderr(waitMs: Long = 300): List<String> {
        Thread.sleep(waitMs)
        val result = mutableListOf<String>()
        stderrQueue.drainTo(result)
        return result
    }

    val isAlive: Boolean get() = process.isAlive

    fun exitCode(): Int? = if (!process.isAlive) runCatching { process.exitValue() }.getOrNull() else null

    /** Non-destructive snapshot of buffered stdout lines (does not affect pollLine). */
    fun peekStdout(): List<String> = responseQueue.toList()

    /** Drains and returns all buffered stdout lines. Optionally waits [waitMs] first. */
    fun drainStdout(waitMs: Long = 0): List<String> {
        if (waitMs > 0) Thread.sleep(waitMs)
        val result = mutableListOf<String>()
        responseQueue.drainTo(result)
        return result
    }

    override fun close() {
        runCatching { writer.close() }
        process.destroy()
    }

    private fun thread(name: String, block: () -> Unit): Thread =
        Thread(block, name).also { it.isDaemon = true; it.start() }
}
