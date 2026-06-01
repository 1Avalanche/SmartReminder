package com.anastasiyaa.smartreminder

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject

data class ChatResponse(
    val content: String,
    val model: String,
    val finishReason: String?,
)

object ApiSample {
    private const val TAG = "ApiSample"
    private const val OKHTTP_TAG = "OkHttp"
    private const val DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"
    private const val MODEL = "deepseek-chat"
    private const val MAX_TOKENS = 1024

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor { message -> Log.d(OKHTTP_TAG, message) }
                .apply { level = HttpLoggingInterceptor.Level.BODY }
        )
        .build()

    suspend fun ask(prompt: String): Result<ChatResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val messages = JSONArray()
                .put(JSONObject().put("role", "user").put("content", prompt))
            val requestJson = JSONObject()
                .put("model", MODEL)
                .put("max_tokens", MAX_TOKENS)
                .put("messages", messages)
                .toString()

            val request = Request.Builder()
                .url(DEEPSEEK_URL)
                .addHeader("Authorization", "Bearer ${BuildConfig.API_KEY}")
                .addHeader("Content-Type", "application/json")
                .post(requestJson.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty()
                    error("HTTP ${response.code}: ${response.message}\n$errBody")
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                ChatResponse(
                    content = content,
                    model = json.optString("model"),
                    finishReason = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .optString("finish_reason")
                        .takeIf { it.isNotEmpty() },
                )
            }
        }.onSuccess { resp ->
            Log.i(TAG, "ask ok: model=${resp.model} finish=${resp.finishReason} content=\"${resp.content.take(80)}\"")
        }.onFailure { e ->
            Log.w(TAG, "ask failed: ${e.message}", e)
        }
    }
}
