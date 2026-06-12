package smartagent

import java.io.File
import kotlinx.serialization.encodeToString

internal class ChatSession {
    companion object {
        const val COMPRESS_AFTER_N_EXCHANGES = 5
        private const val WINDOW_CAPACITY = COMPRESS_AFTER_N_EXCHANGES - 1
    }

    var currentModel: ModelConfig = ModelConfig.DEEPSEEK
        private set

    var currentMode: AgentMode = AgentMode.CHAT
        private set

    var compressionMode: CompressionMode = CompressionMode.NONE

    val currentSystemPrompt: String get() = currentMode.systemPrompt

    var repoContext: RepoContext? = null

    var summary: String = ""
        private set

    private val conversationWindow = ArrayDeque<Pair<String, String>>()

    private val messages = mutableListOf(Message("system", currentSystemPrompt))
    private val history = mutableListOf<LogEntry>()
    private val fileContext = mutableListOf<Pair<String, String>>()

    private val contextFile: File by lazy {
        val path = listOf("cli/context.json", "context.json")
            .firstOrNull { File(it).parentFile?.exists() ?: true }
            ?: "context.json"
        File(path)
    }

    private val tokensFile: File by lazy {
        val path = listOf("cli/tokens.json", "tokens.json")
            .firstOrNull { File(it).parentFile?.exists() ?: true }
            ?: "tokens.json"
        File(path)
    }

    private val tokenEntries = mutableListOf<TokenEntry>()

    init {
        if (contextFile.exists()) {
            val text = contextFile.readText()
            // try new format first, fall back to legacy plain array
            runCatching {
                json.decodeFromString<ContextFile>(text)
            }.getOrNull()?.let { ctx ->
                history.addAll(ctx.history)
                summary = ctx.summary
                compressionMode = ctx.compressionMode
                currentMode = ctx.agentMode
            } ?: runCatching {
                json.decodeFromString<List<LogEntry>>(text)
            }.getOrNull()?.let { history.addAll(it) }
        }
        if (tokensFile.exists()) {
            runCatching {
                json.decodeFromString<List<TokenEntry>>(tokensFile.readText())
            }.getOrNull()?.let { tokenEntries.addAll(it) }
        }
        // window reconstructed from last WINDOW_CAPACITY history entries
        history.takeLast(WINDOW_CAPACITY).forEach { entry ->
            val assistantText = runCatching {
                json.decodeFromString<StructuredResponse>(entry.apiResponse).content
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: entry.apiResponse
            conversationWindow.addLast(Pair(entry.userInput, assistantText))
        }
    }

    fun switchModel(model: ModelConfig) {
        currentModel = model
    }

    fun switchMode(mode: AgentMode) {
        currentMode = mode
        messages.clear()
        messages.add(Message("system", currentSystemPrompt))
        saveContext()
    }

    fun clear() {
        messages.clear()
        messages.add(Message("system", currentSystemPrompt))
        history.clear()
        fileContext.clear()
        summary = ""
        conversationWindow.clear()
        tokenEntries.clear()
        NetworkLogger.clear()
        saveContext()
        runCatching { tokensFile.writeText("[]") }
    }

    fun switchCompression(mode: CompressionMode) {
        compressionMode = mode
        if (mode == CompressionMode.COMPRESS) {
            conversationWindow.clear()
            history.takeLast(WINDOW_CAPACITY).forEach { entry ->
                val assistantText = runCatching {
                    json.decodeFromString<StructuredResponse>(entry.apiResponse).content
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: entry.apiResponse
                conversationWindow.addLast(Pair(entry.userInput, assistantText))
            }
        }
        saveContext()
    }

    fun updateSummary(value: String) {
        summary = value
        saveContext()
    }

    fun peekEviction(): Pair<String, String>? =
        if (conversationWindow.size >= WINDOW_CAPACITY) conversationWindow.first() else null

    fun addExchange(userText: String, assistantText: String) {
        conversationWindow.addLast(Pair(userText, assistantText))
        if (conversationWindow.size > WINDOW_CAPACITY) conversationWindow.removeFirst()
    }

    fun addFileToContext(path: String, content: String) {
        fileContext.removeIf { it.first == path }
        fileContext.add(Pair(path, content))
    }

    fun clearFileContext() = fileContext.clear()

    fun getFileContextPaths(): List<String> = fileContext.map { it.first }

    fun buildFileContextMessages(): List<Message> {
        if (fileContext.isEmpty()) return emptyList()
        val sb = StringBuilder("Файлы из репозитория для контекста:\n\n")
        fileContext.forEach { (path, content) ->
            sb.appendLine("### $path")
            sb.appendLine("```")
            sb.appendLine(content)
            sb.appendLine("```")
        }
        return listOf(Message("user", sb.toString().trimEnd()))
    }

    fun addUserMessage(text: String) {
        messages.add(Message("user", text))
    }

    fun addLogEntry(entry: LogEntry) {
        history.add(entry)
        saveContext()
    }

    fun addTokenEntry(usage: Usage) {
        val entry = TokenEntry(
            request = tokenEntries.size + 1,
            prompt = usage.prompt_tokens,
            completion = usage.completion_tokens,
            total = usage.total_tokens
        )
        tokenEntries.add(entry)
        runCatching { tokensFile.writeText(json.encodeToString(tokenEntries.toList())) }
    }

    fun getTokenEntries(): List<TokenEntry> = tokenEntries.toList()

    fun getHistory(): List<LogEntry> = history.toList()

    fun windowSize(): Int = conversationWindow.size

    fun buildContextContent(): String = when (compressionMode) {
        CompressionMode.NONE -> buildContextNone()
        CompressionMode.COMPRESS -> buildContextCompress()
    }

    private fun buildContextNone(): String {
        val lastTen = history.takeLast(10)
            .mapNotNull { entry ->
                try { json.decodeFromString<StructuredResponse>(entry.apiResponse) }
                catch (_: Exception) { null }
            }
        if (lastTen.isEmpty()) return ""
        return buildString {
            lastTen.forEach {
                appendLine("Вопрос: ${it.summaryRequest}")
                appendLine("Ответ: ${it.summaryResponse}")
                appendLine()
            }
        }.trimEnd()
    }

    private fun buildContextCompress(): String {
        if (summary.isEmpty() && conversationWindow.isEmpty()) return ""
        return buildString {
            if (summary.isNotEmpty()) {
                appendLine("Ранее говорили о: $summary")
                appendLine()
            }
            conversationWindow.forEach { (user, asst) ->
                appendLine("Вопрос: $user")
                appendLine("Ответ: $asst")
                appendLine()
            }
        }.trimEnd()
    }

    fun parseResponse(raw: String): Pair<String, StructuredResponse?> {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val parsed = json.decodeFromString<StructuredResponse>(trimmed)
            Pair(parsed.content, parsed)
        } catch (_: Exception) {
            Pair(raw, null)
        }
    }

    private fun saveContext() {
        runCatching {
            contextFile.writeText(json.encodeToString(ContextFile(history.toList(), summary, compressionMode, currentMode)))
        }
    }
}
