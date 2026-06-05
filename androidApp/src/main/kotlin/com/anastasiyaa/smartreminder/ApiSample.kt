package com.anastasiyaa.smartreminder

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
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
    val elapsedMs: Long,
    val totalTokens: Int?,
    val cost: String?,
)

data class HistoryItem(
    val prompt: String,
    val maxTokens: MaxTokens,
    val temperature: Double?,
    val answerFormat: AnswerFormat,
    val maxCharacters: MaxCharacters,
    val stopSequence: StopSequence,
    val requestBody: String,
    val result: Result<ChatResponse>,
)

object ApiSample {
    private const val TAG = "ApiSample"
    private const val OKHTTP_TAG = "OkHttp"
    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor { message -> Log.d(OKHTTP_TAG, message) }
                .apply {
                    level = HttpLoggingInterceptor.Level.BODY
                    redactHeader("Authorization")
                    redactHeader("x-api-key")
                }
        )
        .build()

    private val _history = mutableStateListOf<HistoryItem>()
    val history: List<HistoryItem> get() = _history

    suspend fun ask(
        prompt: String,
        model: Model = Model.DeepSeekR1,
        maxTokens: MaxTokens = MaxTokens.None,
        temperature: Double? = null,
        answerFormat: AnswerFormat = AnswerFormat.None,
        maxCharacters: MaxCharacters = MaxCharacters.None,
        stopSequence: StopSequence = StopSequence(""),
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        val messages = JSONArray()
        if (answerFormat != AnswerFormat.None) {
            messages.put(
                JSONObject()
                    .put("role", answerFormat.promptLevel.query)
                    .put("content", answerFormat.queryText)
            )
        }
        if (maxCharacters != MaxCharacters.None) {
            messages.put(
                JSONObject()
                    .put("role", maxCharacters.promptLevel.query)
                    .put("content", maxCharacters.queryText)
            )
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        val requestJson = JSONObject().apply {
            put("model", model.modelId)
            put("messages", messages)
            maxTokens.value?.let { put("max_tokens", it) }
            temperature?.let { put("temperature", it) }
            if (stopSequence.value.isNotEmpty()) {
                put("stop", JSONArray().put(stopSequence.value))
            }
        }
        val requestBodyString = requestJson.toString()

        val result = runCatching {
            val startMs = System.currentTimeMillis()
            val request = Request.Builder()
                .url(OPENROUTER_URL)
                .addHeader("Authorization", "Bearer ${BuildConfig.OPENROUTER_API_KEY}")
                .addHeader("Content-Type", "application/json")
                .post(requestBodyString.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty()
                    error("HTTP ${response.code}: ${response.message}\n$errBody")
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val elapsedMs = System.currentTimeMillis() - startMs
                val usage = json.optJSONObject("usage")
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
                    elapsedMs = elapsedMs,
                    totalTokens = usage?.optInt("total_tokens"),
                    cost = usage?.optDouble("cost")?.takeIf { it > 0 }?.let { "%.6f".format(it) },
                )
            }
        }.onSuccess { resp ->
            Log.i(TAG, "ask ok: model=${resp.model} finish=${resp.finishReason} content=\"${resp.content.take(80)}\"")
        }.onFailure { e ->
            Log.w(TAG, "ask failed: ${e.message}", e)
        }
        _history.add(
            HistoryItem(
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature,
                answerFormat = answerFormat,
                maxCharacters = maxCharacters,
                stopSequence = stopSequence,
                requestBody = requestJson.toString(2),
                result = result,
            )
        )
        result
    }
}

private enum class PromptLevel(val query: String) {
    System("system"), Assistant("assistant"), User("user")
}

private val AnswerFormat.promptLevel: PromptLevel
    get() = PromptLevel.System

private val AnswerFormat.queryText: String
    get() = when (this) {
        AnswerFormat.None -> ""
        AnswerFormat.JSON -> "Отвечай строго в JSON формате"
        AnswerFormat.RuText -> "Отвечай только на русском языке вне зависимости от того, на каком был задан вопрос."
        AnswerFormat.EnText -> "Отвечай только на английском языке вне зависимости от того, на каком был задан вопрос."
    }

private val MaxCharacters.promptLevel: PromptLevel
    get() = PromptLevel.System

private val MaxCharacters.queryText: String
    get() = value?.let { "Ответ должен содержать максимум $it символов. Превышать лимит запрещено." } ?: ""