package smartagent.mcp_handler

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ProcessTransport(command: List<String>, workDir: String) : McpTransport {
    private val process: Process
    private val writer: BufferedWriter
    private val responseQueue = LinkedBlockingQueue<String>()
    private val stderrQueue = LinkedBlockingQueue<String>()

    init {
        process = ProcessBuilder(command)
            .directory(File(workDir))
            .start()

        writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))

        // Reads server stdout in background; each newline-delimited JSON line → queue
        thread("mcp-stdout") {
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) responseQueue.put(line)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) { /* stream closed */ }
            }
        }

        // Buffer server stderr; caller drains it synchronously via drainStderr()
        thread("mcp-stderr") {
            BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use { reader ->
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        stderrQueue.put(line)
                    }
                } catch (e: Exception) { /* stream closed */ }
            }
        }
    }

    override fun send(message: String) {
        writer.write(message)
        writer.newLine()
        writer.flush()
    }

    /** Blocks up to [timeoutMs] for next line from server stdout. Returns null on timeout. */
    override fun pollLine(timeoutMs: Long): String? =
        responseQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)

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

    override fun close() {
        runCatching { writer.close() }
        process.destroy()
    }

    private fun thread(name: String, block: () -> Unit): Thread =
        Thread(block, name).also { it.isDaemon = true; it.start() }
}
