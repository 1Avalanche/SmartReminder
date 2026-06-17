package smartagent

import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
        val activeCall = AtomicReference<okhttp3.Call?>(null)
        val escCanceller = EscCanceller { activeCall.get()?.cancel() }
        val watcherThread = escCanceller.start()
        try {
            val fileContextContent = session.buildFileContextMessages().firstOrNull()?.content ?: ""
            val estimatedChars = session.currentSystemPrompt.length +
                session.buildContextContent().length + fileContextContent.length + text.length
            SummaryAgent(session).compressIfNeeded(estimatedChars)

            val userContent = if (fileContextContent.isNotEmpty()) "$fileContextContent\n\n$text" else text
            var retried = false

            while (true) {
                val contextContent = session.buildContextContent()
                val fullMessages = buildList {
                    add(Message("system", session.currentSystemPrompt))
                    if (contextContent.isNotEmpty()) add(Message("assistant", contextContent))
                    add(timestampMessage())
                    add(Message("user", userContent))
                }
                requestBody = json.encodeToString(ChatRequest(session.currentModel.apiModelId, fullMessages))
                val request = buildRequest(requestBody, apiKey)
                val call = http.newCall(request)
                activeCall.set(call)
                val response = call.execute()

                val body = response.body?.string() ?: ""
                val reqHeaders = request.headers.toMap()
                val resHeaders = response.headers.toMap()

                if (!response.isSuccessful) {
                    if (response.code == 400 && !retried && isContextOverflow(body)) {
                        retried = true
                        NetworkLogger.log(session.currentModel.url, reqHeaders, requestBody, response.code, resHeaders, body, "[MAIN_AGENT]")
                        SummaryAgent(session).forceCompress()
                        continue
                    }
                    spinner.stop()
                    NetworkLogger.log(session.currentModel.url, reqHeaders, requestBody, response.code, resHeaders, body, "[MAIN_AGENT]")
                    println("Error ${response.code}: ${body.ifEmpty { "Unknown error" }}")
                    session.addLogEntry(LogEntry(text, requestBody, body.ifEmpty { "HTTP ${response.code}" }))
                    return
                }

                val chatResponse = json.decodeFromString<ChatResponse>(body)
                val reply = chatResponse.choices.firstOrNull()?.message?.content ?: ""
                val usage = chatResponse.usage

                if (reply.isBlank()) {
                    spinner.stop()
                    NetworkLogger.log(session.currentModel.url, reqHeaders, requestBody, response.code, resHeaders, body, "[MAIN_AGENT]")
                    println("Warning: empty response from the model")
                    session.addLogEntry(LogEntry(text, requestBody, body))
                } else {
                    val (displayText, structured) = session.parseResponse(reply)
                    val responseForLog = json.encodeToString(structured ?: StructuredResponse(content = reply))
                    NetworkLogger.log(session.currentModel.url, reqHeaders, requestBody, response.code, resHeaders, body, "[MAIN_AGENT]")
                    spinner.stop()
                    println()
                    println()
                    println("${Colors.LIGHT_VIOLET}$displayText${Colors.RESET}\n")
                    if (usage != null) {
                        val pct = usage.prompt_tokens * 100 / session.currentModel.contextWindow
                        println(Colors.LIGHT_YELLOW + "tokens → prompt: ${usage.prompt_tokens} | completion: ${usage.completion_tokens} | total: ${usage.total_tokens} | context: $pct%" + Colors.RESET)
                        session.addTokenEntry(usage)
                        session.updateLastPromptTokens(usage.prompt_tokens)
                    }
                    session.addLogEntry(LogEntry(text, requestBody, responseForLog))
                    if (session.shouldTriggerProfile()) Thread {
                        ProfileAgent(session).update()
                    }.apply { isDaemon = true }.start()
                }
                break
            }
        } catch (e: Exception) {
            spinner.stop()
            if (escCanceller.wasCancelled) {
                println("\n${Colors.LIGHT_YELLOW}Отменено.${Colors.RESET}")
                return
            }
            NetworkLogger.log(session.currentModel.url, emptyMap(), requestBody, 0, emptyMap(), "Error: ${e.message}", "[MAIN_AGENT]")
            println("Error: ${e.message}")
            session.addLogEntry(LogEntry(text, requestBody, "Error: ${e.message}"))
        } finally {
            escCanceller.stop()
            watcherThread.join(300)
        }
    }

    private fun isContextOverflow(body: String) =
        body.contains("context length", ignoreCase = true) ||
        body.contains("context_length", ignoreCase = true)

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
