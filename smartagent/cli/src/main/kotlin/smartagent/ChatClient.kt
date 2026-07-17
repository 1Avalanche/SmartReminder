package smartagent

import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class ChatClient(private val session: ChatSession) {
    var chatSetting: ChatSetting = ChatSetting.NO

    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val chatOptimumPrompt: String by lazy {
        listOf(
            "cli/src/main/kotlin/prompts/architect/chat_optimum.md",
            "smartagent/cli/src/main/kotlin/prompts/architect/chat_optimum.md",
            "src/main/kotlin/prompts/architect/chat_optimum.md",
            "prompts/architect/chat_optimum.md"
        ).map(::File).firstOrNull { it.exists() }?.readText()?.trim() ?: ""
    }

    fun sendMessage(
        text: String,
        systemPromptOverride: String? = null,
        includeHistory: Boolean = true,
        saveToHistory: Boolean = true
    ): String? {
        val isChatMode = session.currentMode == AgentMode.CHAT
        val effectiveModel = session.currentModel

        val apiKey = Config.apiKey(effectiveModel)
        if (apiKey.isNullOrBlank()) {
            println("Error: ${effectiveModel.apiKeyProperty} not found in local.properties or environment")
            return null
        }

        val effectiveSystemPrompt: String? = when {
            isChatMode && chatSetting == ChatSetting.NO -> null
            isChatMode && chatSetting == ChatSetting.OPTIMUM -> chatOptimumPrompt.ifBlank { null }
            else -> systemPromptOverride ?: session.currentSystemPrompt
        }

        val requestTemperature: Double? = if (isChatMode && chatSetting == ChatSetting.OPTIMUM) 0.2 else null
        val requestMaxTokens: Int? = if (isChatMode && chatSetting == ChatSetting.OPTIMUM) 1024 else null
        val requestOptions: OllamaOptions? = if (isChatMode && chatSetting == ChatSetting.OPTIMUM) OllamaOptions(num_ctx = 8192) else null

        val spinner = Spinner()
        var requestBody = ""
        val activeCall = AtomicReference<okhttp3.Call?>(null)
        val escCanceller = EscCanceller { activeCall.get()?.cancel() }
        val watcherThread = escCanceller.start()
        try {
            val fileContextContent = session.buildFileContextMessages().firstOrNull()?.content ?: ""
            val historyContent = if (includeHistory) session.buildContextContent() else ""
            val estimatedChars = (effectiveSystemPrompt?.length ?: 0) +
                historyContent.length + fileContextContent.length + text.length
            SummaryAgent(session).compressIfNeeded(estimatedChars)

            val userContent = if (fileContextContent.isNotEmpty()) "$fileContextContent\n\n$text" else text
            var retried = false

            while (true) {
                val contextContent = if (includeHistory) session.buildContextContent() else ""
                val fullMessages = buildList {
                    if (!effectiveSystemPrompt.isNullOrBlank()) add(Message("system", effectiveSystemPrompt))
                    if (contextContent.isNotEmpty()) add(Message("assistant", contextContent))
                    add(timestampMessage())
                    add(Message("user", userContent))
                }
                requestBody = json.encodeToString(ChatRequest(
                    model = effectiveModel.apiModelId,
                    messages = fullMessages,
                    temperature = requestTemperature,
                    max_tokens = requestMaxTokens,
                    options = requestOptions
                ))
                val request = buildRequest(requestBody, apiKey, Config.apiUrl(effectiveModel))
                val call = http.newCall(request)
                activeCall.set(call)
                val response = call.execute()

                val body = response.body?.string() ?: ""
                val reqHeaders = request.headers.toMap()
                val resHeaders = response.headers.toMap()

                if (!response.isSuccessful) {
                    if (response.code == 400 && !retried && isContextOverflow(body)) {
                        retried = true
                        NetworkLogger.log(Config.apiUrl(effectiveModel), reqHeaders, requestBody, response.code, resHeaders, body, "[MAIN_AGENT]")
                        SummaryAgent(session).forceCompress()
                        continue
                    }
                    spinner.stop()
                    NetworkLogger.log(Config.apiUrl(effectiveModel), reqHeaders, requestBody, response.code, resHeaders, body, "[MAIN_AGENT]")
                    println("Error ${response.code}: ${body.ifEmpty { "Unknown error" }}")
                    if (saveToHistory) session.addLogEntry(LogEntry(text, requestBody, body.ifEmpty { "HTTP ${response.code}" }))
                    return null
                }

                val chatResponse = json.decodeFromString<ChatResponse>(body)
                val reply = chatResponse.choices.firstOrNull()?.message?.content ?: ""
                val usage = chatResponse.usage

                if (reply.isBlank()) {
                    spinner.stop()
                    NetworkLogger.log(Config.apiUrl(effectiveModel), reqHeaders, requestBody, response.code, resHeaders, body, "[MAIN_AGENT]")
                    println("Warning: empty response from the model")
                    if (saveToHistory) session.addLogEntry(LogEntry(text, requestBody, body))
                    return null
                } else {
                    val (displayText, structured) = session.parseResponse(reply)
                    val responseForLog = json.encodeToString(structured ?: StructuredResponse(content = reply))
                    NetworkLogger.log(Config.apiUrl(effectiveModel), reqHeaders, requestBody, response.code, resHeaders, body, "[MAIN_AGENT]")
                    spinner.stop()
                    println()
                    println()
                    val rendered = displayText.replace(Regex("""\*\*(.+?)\*\*""")) {
                        "${Colors.BOLD}${it.groupValues[1]}${Colors.BOLD_OFF}"
                    }
                    println("${Colors.LIGHT_VIOLET}$rendered${Colors.RESET}\n")
                    if (usage != null) {
                        val pct = usage.prompt_tokens * 100 / effectiveModel.contextWindow
                        println(Colors.LIGHT_YELLOW + "tokens → prompt: ${usage.prompt_tokens} | completion: ${usage.completion_tokens} | total: ${usage.total_tokens} | context: $pct%" + Colors.RESET)
                        println(Colors.LIGHT_YELLOW + "model  → ${effectiveModel.shortName}" + Colors.RESET)
                        session.addTokenEntry(usage)
                        session.updateLastPromptTokens(usage.prompt_tokens)
                    }
                    if (saveToHistory) {
                        session.addLogEntry(LogEntry(text, requestBody, responseForLog))
                        if (session.shouldTriggerProfile()) Thread {
                            ProfileAgent(session).update()
                        }.apply { isDaemon = true }.start()
                    }
                    return displayText
                }
            }
        } catch (e: Exception) {
            spinner.stop()
            if (escCanceller.wasCancelled) {
                println("\n${Colors.LIGHT_YELLOW}Отменено.${Colors.RESET}")
                return null
            }
            val url = Config.apiUrl(session.currentModel)
            NetworkLogger.log(url, emptyMap(), requestBody, 0, emptyMap(), "Error: ${e.message}", "[MAIN_AGENT]")
            println("Error: ${e.message}")
            if (saveToHistory) session.addLogEntry(LogEntry(text, requestBody, "Error: ${e.message}"))
            return null
        } finally {
            escCanceller.stop()
            watcherThread.join(300)
        }
    }

    private fun isContextOverflow(body: String) =
        body.contains("context length", ignoreCase = true) ||
        body.contains("context_length", ignoreCase = true)

    private fun buildRequest(body: String, apiKey: String, url: String): Request =
        Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
}

private fun okhttp3.Headers.toMap(): Map<String, String> = buildMap {
    forEach { (name, value) -> put(name, value) }
}
