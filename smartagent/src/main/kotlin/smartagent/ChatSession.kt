package smartagent

import java.io.File
import kotlinx.serialization.encodeToString

internal class ChatSession {
    companion object {
        const val SLIDING_WINDOW_SIZE = 6
    }

    var currentModel: ModelConfig = ModelConfig.DEEPSEEK
        private set

    var currentMode: AgentMode = AgentMode.CHAT
        private set

    val currentSystemPrompt: String
        get() = "${currentMode.basePrompt}\n${CONTEXT_FORMAT_INSTRUCTION}"

    var repoContext: RepoContext? = null

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
            runCatching {
                json.decodeFromString<ContextFile>(text)
            }.getOrNull()?.let { ctx ->
                history.addAll(ctx.history)
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
    }

    fun switchModel(model: ModelConfig) {
        currentModel = model
    }

    fun switchMode(mode: AgentMode) {
        history.clear()
        currentMode = mode
        saveContext()
    }

    fun clear() {
        history.clear()
        fileContext.clear()
        tokenEntries.clear()
        NetworkLogger.clear()
        saveContext()
        runCatching { tokensFile.writeText("[]") }
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

    fun addLogEntry(entry: LogEntry) {
        history.add(entry)
        if (history.size > SLIDING_WINDOW_SIZE) {
            history.removeFirst()
        }
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

    fun buildContextContent(): String = buildContextSlidingWindow()

    private fun buildContextSlidingWindow(): String {
        val lastTen = history.takeLast(SLIDING_WINDOW_SIZE)
            .mapNotNull { entry ->
                try { json.decodeFromString<StructuredResponse>(entry.apiResponse) }
                catch (_: Exception) { null }
            }
        if (lastTen.isEmpty()) return ""
        return buildString {
            appendLine("История сообщений:")
            lastTen.forEach {
                appendLine("Вопрос: ${it.summaryRequest}")
                appendLine("Ответ: ${it.summaryResponse}")
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
            contextFile.writeText(
                prettyJson.encodeToString(
                    ContextFile(
                        history = history.toList(),
                        agentMode = currentMode
                    )
                )
            )
        }
    }
}
