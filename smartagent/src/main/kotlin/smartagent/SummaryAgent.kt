package smartagent

import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

internal class SummaryAgent(private val session: ChatSession) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun compressIfNeeded(estimatedChars: Int = 0) {
        if (!session.shouldCompress(estimatedChars)) return
        val toSummarize = session.getMessagesToSummarize()
        if (toSummarize.isEmpty()) return
        compress(toSummarize)
    }

    fun forceCompress() {
        val toSummarize = session.getMessagesToSummarize()
        if (toSummarize.isEmpty()) return
        compress(toSummarize)
    }

    private fun compress(entries: List<LogEntry>) {
        val apiKey = Config.apiKey(session.currentModel) ?: return
        val systemPrompt = loadSystemPrompt()

        val userContent = buildString {
            if (session.summary.isNotBlank()) {
                appendLine("Текущее summary:")
                appendLine(session.summary)
                appendLine()
            }
            appendLine("Сообщения для суммаризации:")
            entries.forEach { entry ->
                val content = runCatching {
                    json.decodeFromString<StructuredResponse>(entry.apiResponse).content
                }.getOrElse { entry.apiResponse }
                appendLine("Вопрос: ${entry.userInput}")
                appendLine("Ответ: $content")
                appendLine()
            }
        }.trimEnd()

        val messages = listOf(
            Message("system", systemPrompt),
            Message("user", userContent)
        )
        val requestBody = json.encodeToString(ChatRequest(session.currentModel.apiModelId, messages))

        val request = Request.Builder()
            .url(session.currentModel.url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        runCatching {
            val response = http.newCall(request).execute()
            val body = response.body?.string() ?: ""
            NetworkLogger.log(
                url = session.currentModel.url,
                reqHeaders = request.headers.toMap(),
                reqBody = requestBody,
                statusCode = response.code,
                resHeaders = response.headers.toMap(),
                resBody = body,
                source = "[SUMMARY_AGENT]"
            )

            if (response.isSuccessful) {
                val chatResponse = json.decodeFromString<ChatResponse>(body)
                val newSummary = chatResponse.choices.firstOrNull()?.message?.content?.trim() ?: return
                if (newSummary.isNotBlank()) {
                    session.applySummary(newSummary, entries.size)
                    chatResponse.usage?.let { session.updateLastPromptTokens(it.prompt_tokens) }
                }
            }
        }
    }

    private fun loadSystemPrompt(): String {
        val paths = listOf(
            "smartagent/src/main/kotlin/prompts/summary_system.md",
            "src/main/kotlin/prompts/summary_system.md",
            "prompts/summary_system.md"
        )
        return paths.firstOrNull { File(it).exists() }?.let { File(it).readText() }
            ?: "Создай компактное summary разговора, сохраняя цели, решения, ограничения и прогресс."
    }
}

private fun okhttp3.Headers.toMap(): Map<String, String> = buildMap {
    forEach { (name, value) -> put(name, value) }
}
