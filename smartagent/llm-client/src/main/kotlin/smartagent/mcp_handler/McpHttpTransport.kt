package smartagent.mcp_handler

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * HTTP transport for remote MCP servers.
 *
 * Supports both plain JSON responses and SSE (Streamable HTTP) responses.
 * SSE bodies are parsed by extracting `data:` lines; plain JSON is passed through.
 * Each JSON-RPC request becomes a POST to [serverUrl].
 * Notifications (no "id") are fire-and-forget: response is not enqueued.
 */
class McpHttpTransport(
    private val serverUrl: String,
    private val apiKey: String? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
) : McpTransport {

    private val responseQueue = LinkedBlockingQueue<String>()
    private val json = Json { ignoreUnknownKeys = true }

    override fun send(message: String) {
        val isNotification = try {
            !json.parseToJsonElement(message).jsonObject.containsKey("id")
        } catch (_: Exception) {
            false
        }

        val body = message.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(serverUrl)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply { apiKey?.let { header("Authorization", "Bearer $it") } }
            .build()

        val responseBody = httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) println("[MCP HTTP] ${resp.code} error from $serverUrl")
            resp.body?.string()
        }

        if (!isNotification && !responseBody.isNullOrBlank()) {
            extractJsonLines(responseBody).forEach { responseQueue.put(it) }
        }
    }

    override fun pollLine(timeoutMs: Long): String? =
        responseQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        httpClient.dispatcher.executorService.shutdown()
    }

    companion object {
        /**
         * Extracts JSON-RPC lines from either a plain JSON body or an SSE body.
         * SSE lines look like `data: {...}` — the `data: ` prefix is stripped.
         */
        internal fun extractJsonLines(body: String): List<String> {
            val trimmed = body.trimStart()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return listOf(body)
            return body.lines()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .filter { it.isNotBlank() && it != "[DONE]" }
        }
    }
}
