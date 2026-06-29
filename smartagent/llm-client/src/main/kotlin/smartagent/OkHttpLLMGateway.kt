package smartagent

import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OkHttpLLMGateway : LLMGateway {
    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun chat(messages: List<Message>, model: ModelConfig, source: String): LLMGateway.Response? {
        val apiKey = Config.apiKey(model) ?: return null
        val requestBody = json.encodeToString(ChatRequest(model.apiModelId, messages))
        val request = Request.Builder()
            .url(model.url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        return runCatching {
            val startMs = System.currentTimeMillis()
            val response = http.newCall(request).execute()
            val durationMs = System.currentTimeMillis() - startMs
            val body = response.body?.string() ?: ""
            val chatResponse = runCatching { json.decodeFromString<ChatResponse>(body) }.getOrNull()
            NetworkLogger.logRequest(
                source = source,
                url = model.url,
                reqHeaders = request.headers.toMap(),
                reqBody = requestBody,
                statusCode = response.code,
                resHeaders = response.headers.toMap(),
                resBody = body,
                durationMs = durationMs,
                usage = chatResponse?.usage
            )
            if (!response.isSuccessful) return@runCatching null
            val content = chatResponse?.choices?.firstOrNull()?.message?.content?.trim()
                ?: return@runCatching null
            LLMGateway.Response(content, chatResponse.usage)
        }.getOrNull()
    }
}

private fun okhttp3.Headers.toMap(): Map<String, String> = buildMap {
    forEach { (name, value) -> put(name, value) }
}
