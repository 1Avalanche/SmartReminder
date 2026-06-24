package smartagent.telegram.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.concurrent.TaskRunner.Companion.logger
import java.util.concurrent.TimeUnit

class TelegramApiClient(token: String) {

    private val baseUrl = "https://api.telegram.org/bot$token"
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)  // must exceed long-poll timeout (30s)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun getUpdates(offset: Long): List<TelegramUpdate> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/getUpdates?offset=$offset&timeout=30&allowed_updates=%5B%22message%22%5D")
                .get()
                .build()
            val body = http.newCall(request).execute().use { it.body?.string() }
                ?: return@runCatching emptyList()

            val response = json.decodeFromString<GetUpdatesResponse>(body)
            if (!response.ok) {
                println("[TelegramApiClient] getUpdates warning: ok=false, error=${response.errorCode}, description=${response.description}")
                delay(5000)
                return@runCatching emptyList()
            }
            response.result
        }.getOrElse { e ->
            println("[TelegramApiClient] getUpdates error: ${e.message}")
            delay(5000)
            emptyList()
        }
    }

    suspend fun sendMessage(chatId: Long, text: String): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val payload = json.encodeToString(SendMessageRequest(chatId, text))
            val request = Request.Builder()
                .url("$baseUrl/sendMessage")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().close()
        }.onFailure { e ->
            println("[TelegramApiClient] sendMessage error: ${e.message}")
        }
    }
}
