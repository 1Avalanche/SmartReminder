package smartagent

import java.io.File
import kotlinx.serialization.encodeToString

internal class ChatSession {
    var currentModel: ModelConfig = ModelConfig.DEEPSEEK
        private set

    var currentMode: AgentMode = AgentMode.CHAT
        private set

    val currentSystemPrompt: String get() = currentMode.systemPrompt

    var repoContext: RepoContext? = null

    private val messages = mutableListOf(Message("system", currentSystemPrompt))
    private val history = mutableListOf<LogEntry>()
    private val fileContext = mutableListOf<Pair<String, String>>()

    private val historyFile: File by lazy {
        val path = listOf("cli/context.json", "context.json")
            .firstOrNull { File(it).parentFile?.exists() ?: true }
            ?: "context.json"
        File(path)
    }

    init {
        if (historyFile.exists()) {
            runCatching {
                json.decodeFromString<List<LogEntry>>(historyFile.readText())
            }.getOrNull()?.let { history.addAll(it) }
        }
    }

    fun switchModel(model: ModelConfig) {
        currentModel = model
    }

    fun switchMode(mode: AgentMode) {
        currentMode = mode
        messages.clear()
        messages.add(Message("system", currentSystemPrompt))
    }

    fun clear() {
        messages.clear()
        messages.add(Message("system", currentSystemPrompt))
        history.clear()
        fileContext.clear()
        NetworkLogger.clear()
        runCatching { historyFile.writeText("[]") }
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
        runCatching { historyFile.writeText(json.encodeToString(history.toList())) }
    }

    fun getHistory(): List<LogEntry> = history.toList()

    fun buildContext(): List<Message> {
        val lastTen = history.takeLast(10)
            .mapNotNull { entry ->
                try { json.decodeFromString<StructuredResponse>(entry.apiResponse) }
                catch (_: Exception) { null }
            }
        if (lastTen.isEmpty()) return emptyList()

        val keywords = lastTen.flatMap { it.keywords }.distinct().joinToString(", ")
        val summaryRequests = lastTen.joinToString("; ") { it.summaryRequest }
        val summaryResponses = lastTen.joinToString("; ") { it.summaryResponse }
        return listOf(
            Message("assistant", "keywords: $keywords"),
            Message("assistant", "ранее пользователь спрашивал о: $summaryRequests"),
            Message("assistant", "ты ответил ранее: $summaryResponses")
        )
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
}
