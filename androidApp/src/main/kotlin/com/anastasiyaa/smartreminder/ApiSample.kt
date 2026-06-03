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
)

data class HistoryItem(
    val prompt: String,
    val maxTokens: MaxTokens,
    val temperature: Temperature,
    val answerFormat: AnswerFormat,
    val maxCharacters: MaxCharacters,
    val stopSequence: StopSequence,
    val requestBody: String,
    val result: Result<ChatResponse>,
)

object ApiSample {
    private const val TAG = "ApiSample"
    private const val OKHTTP_TAG = "OkHttp"
    private const val DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"
    private const val MODEL = "deepseek-chat"

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
        maxTokens: MaxTokens = MaxTokens.None,
        temperature: Temperature = Temperature.None,
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
            put("model", MODEL)
            put("messages", messages)
            maxTokens.value?.let { put("max_tokens", it) }
            temperature.value?.let { put("temperature", it) }
            if (stopSequence.value.isNotEmpty()) {
                put("stop", JSONArray().put(stopSequence.value))
            }
        }
        val requestBodyString = requestJson.toString()

        val result = runCatching {
            val request = Request.Builder()
                .url(DEEPSEEK_URL)
                .addHeader("Authorization", "Bearer ${BuildConfig.API_KEY}")
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