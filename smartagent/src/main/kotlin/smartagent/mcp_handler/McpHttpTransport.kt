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
 * Each JSON-RPC request becomes a POST to [serverUrl].
 * Notifications (no "id") are fire-and-forget: response is not enqueued.
 * [McpClient] consumes responses via [pollLine] exactly like the stdio transport.
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
            .apply { apiKey?.let { header("Authorization", "Bearer $it") } }
            .build()

        val responseBody = httpClient.newCall(request).execute().use { it.body?.string() }

        if (!isNotification && !responseBody.isNullOrBlank()) {
            responseQueue.put(responseBody)
        }
    }

    override fun pollLine(timeoutMs: Long): String? =
        responseQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        httpClient.dispatcher.executorService.shutdown()
    }
}
