package smartagent

import kotlinx.serialization.encodeToString
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal class ArchitectClient(
    private val session: ChatSession,
    private val onboarding: ArchitectOnboarding
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun sendMessage(userInput: String) {
        val apiKey = Config.apiKey(session.currentModel)
        if (apiKey.isNullOrBlank()) {
            println("Error: ${session.currentModel.apiKeyProperty} not found in local.properties or environment")
            return
        }

        val spinner = Spinner()
        var requestBody = ""
        try {
            val messages = buildMessages(userInput)
            requestBody = json.encodeToString(ChatRequest(session.currentModel.apiModelId, messages))

            val request = Request.Builder()
                .url(session.currentModel.url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = http.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val reqHeaders = request.headers.toMap()
            val resHeaders = response.headers.toMap()

            if (!response.isSuccessful) {
                spinner.stop()
                NetworkLogger.log(session.currentModel.url, reqHeaders, requestBody, response.code, resHeaders, body, "[MAIN_AGENT]")
                println("Error ${response.code}: ${body.ifEmpty { "Unknown error" }}")
                return
            }

            val chatResponse = json.decodeFromString<ChatResponse>(body)
            val reply = chatResponse.choices.firstOrNull()?.message?.content ?: ""
            val usage = chatResponse.usage

            NetworkLogger.log(session.currentModel.url, reqHeaders, requestBody, response.code, resHeaders, body, "[MAIN_AGENT]")
            spinner.stop()

            if (reply.isBlank()) {
                println("Warning: empty response from the model")
                return
            }

            val architectResponse = parseArchitectResponse(reply)
            println()
            println("${Colors.LIGHT_VIOLET}${architectResponse.content}${Colors.RESET}\n")

            if (usage != null) {
                val pct = usage.prompt_tokens * 100 / session.currentModel.contextWindow
                println(Colors.LIGHT_YELLOW + "tokens → prompt: ${usage.prompt_tokens} | completion: ${usage.completion_tokens} | total: ${usage.total_tokens} | context: $pct%" + Colors.RESET)
                session.addTokenEntry(usage)
                session.updateLastPromptTokens(usage.prompt_tokens)
            }

            session.addLogEntry(LogEntry(userInput, requestBody, json.encodeToString(architectResponse)))

            architectResponse.decision?.takeIf { it.isNotBlank() }?.let {
                onboarding.appendDecision(it)
            }

            architectResponse.currentTask?.takeIf { it.isNotBlank() }?.let { task ->
                val colon = task.indexOf(':')
                if (colon > 0) {
                    onboarding.upsertWorkTask(task.substring(0, colon).trim(), task.substring(colon + 1).trim())
                }
            }

        } catch (e: Exception) {
            spinner.stop()
            println("Error: ${e.message}")
        }
    }

    private fun buildMessages(userInput: String): List<Message> = buildList {
        add(Message("system", onboarding.buildSystemPrompt()))

        val assistantContext = buildString {
            val historyText = buildHistoryText()
            if (historyText.isNotEmpty()) {
                appendLine("История сообщений:")
                appendLine(historyText)
            }
            val workMemoryText = onboarding.buildWorkMemoryText()
            if (workMemoryText.isNotEmpty()) {
                appendLine("История задач и принятых решений:")
                appendLine(workMemoryText)
            }
            val ts = timestampMessage()
            appendLine(ts.content)
        }.trim()
        add(Message("assistant", assistantContext))

        add(Message("user", userInput))
    }

    private fun buildHistoryText(): String {
        val history = session.getHistory()
        if (history.isEmpty()) return ""
        return history.joinToString("\n") { entry ->
            val content = runCatching {
                json.decodeFromString<ArchitectResponse>(entry.apiResponse).content
            }.getOrElse { entry.apiResponse }
            "Вопрос: ${entry.userInput}\nОтвет: $content"
        }
    }

    private fun parseArchitectResponse(raw: String): ArchitectResponse {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { json.decodeFromString<ArchitectResponse>(trimmed) }.getOrNull()?.let { return it }

        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { json.decodeFromString<ArchitectResponse>(raw.substring(start, end + 1)) }
                .getOrNull()?.let { return it }
        }

        return ArchitectResponse(content = raw)
    }
}

private fun Headers.toMap(): Map<String, String> = buildMap {
    forEach { (name, value) -> put(name, value) }
}
