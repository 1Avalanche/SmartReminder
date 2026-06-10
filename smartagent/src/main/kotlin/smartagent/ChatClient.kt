package smartagent

import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

internal class ChatClient(private val session: ChatSession) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun sendMessage(text: String) {
        val apiKey = Config.apiKey(session.currentModel)
        if (apiKey.isNullOrBlank()) {
            println("Error: ${session.currentModel.apiKeyProperty} not found in local.properties or environment")
            return
        }

        val spinner = Spinner()
        var requestBody = ""
        try {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val fullMessages = listOf(Message("system", session.currentSystemPrompt)) + session.buildContext() +
                session.buildFileContextMessages() +
                Message("assistant", "Current time: $timestamp") + Message("user", text)
            requestBody = json.encodeToString(ChatRequest(session.currentModel.apiModelId, fullMessages))
            val request = buildRequest(requestBody, apiKey)
            val response = http.newCall(request).execute()

            val body = response.body?.string() ?: ""
            val reqHeaders = request.headers.toMap()
            val resHeaders = response.headers.toMap()

            if (!response.isSuccessful) {
                spinner.stop()
                NetworkLogger.log(session.currentModel.url, reqHeaders, requestBody, response.code, resHeaders, body)
                println("Error ${response.code}: ${body.ifEmpty { "Unknown error" }}")
                session.addLogEntry(LogEntry(text, requestBody, body.ifEmpty { "HTTP ${response.code}" }))
                return
            }

            val chatResponse = json.decodeFromString<ChatResponse>(body)
            val reply = chatResponse.choices.firstOrNull()?.message?.content ?: ""
            val usage = chatResponse.usage

            if (reply.isBlank()) {
                spinner.stop()
                NetworkLogger.log(session.currentModel.url, reqHeaders, requestBody, response.code, resHeaders, body)
                println("Warning: empty response from the model")
                session.addLogEntry(LogEntry(text, requestBody, body))
            } else {
                val (displayText, structured) = session.parseResponse(reply)
                val enriched = (structured ?: StructuredResponse(content = reply)).copy(
                    summaryRequest = text,
                    summaryResponse = displayText
                )
                val responseForLog = json.encodeToString(enriched)
                NetworkLogger.log(session.currentModel.url, reqHeaders, requestBody, response.code, resHeaders, body)
                spinner.stop()
                println("\n${Colors.LIGHT_VIOLET}$displayText${Colors.RESET}\n")
                if (usage != null) {
                    println(Colors.LIGHT_YELLOW + "tokens → prompt: ${usage.prompt_tokens} | completion: ${usage.completion_tokens} | total: ${usage.total_tokens}" + Colors.RESET)
                }
                session.addLogEntry(LogEntry(text, requestBody, responseForLog))
                session.addUserMessage(text)
            }
        } catch (e: Exception) {
            spinner.stop()
            NetworkLogger.log(session.currentModel.url, emptyMap(), requestBody, 0, emptyMap(), "Error: ${e.message}")
            println("Error: ${e.message}")
            session.addLogEntry(LogEntry(text, requestBody, "Error: ${e.message}"))
        }
    }

    private fun buildRequest(body: String, apiKey: String): Request =
        Request.Builder()
            .url(session.currentModel.url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
}

private fun okhttp3.Headers.toMap(): Map<String, String> = buildMap {
    forEach { (name, value) -> put(name, value) }
}
