package smartagent

import java.io.File
import kotlinx.serialization.encodeToString

private const val CONTEXT_PERCENT = 0.04
internal class ChatSession {
    var currentModel: ModelConfig = ModelConfig.DEEPSEEK
        private set

    var currentMode: AgentMode = AgentMode.CHAT
        private set

    val currentSystemPrompt: String
        get() = buildString {
            append(currentMode.basePrompt)
            val profile = loadUserProfile()
            if (profile.isNotBlank()) {
                append("\n\n## Долгосрочный профиль пользователя\n$profile")
            }
            append("\n$CONTEXT_FORMAT_INSTRUCTION")
        }

    var repoContext: RepoContext? = null

    var summary: String = ""
        private set

    var lastPromptTokens: Int = 0
        private set

    var userMessageCount: Int = 0
        private set

    private val history = mutableListOf<LogEntry>()
    private val fileContext = mutableListOf<Pair<String, String>>()

    private val contextFile: File by lazy {
        val path = listOf("cli/context.json", "context.json")
            .firstOrNull { File(it).parentFile?.exists() ?: true }
            ?: "context.json"
        File(path)
    }

    val profileFile: File by lazy {
        val path = listOf("cli/user_profile.md", "user_profile.md")
            .firstOrNull { File(it).parentFile?.exists() ?: true }
            ?: "user_profile.md"
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
                summary = ctx.summary
                lastPromptTokens = ctx.lastPromptTokens
                userMessageCount = ctx.userMessageCount
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
        summary = ""
        lastPromptTokens = 0
        userMessageCount = 0
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
        userMessageCount++
        saveContext()
    }

    fun shouldTriggerProfile(): Boolean = userMessageCount > 0 && userMessageCount % 3 == 0

    fun getLastUserInputs(n: Int): List<String> =
        history.takeLast(n).map { it.userInput }

    fun loadUserProfile(): String =
        runCatching { profileFile.readText().trim() }.getOrElse { "" }

    fun saveUserProfile(content: String) {
        runCatching { profileFile.writeText(content) }
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

    fun updateLastPromptTokens(tokens: Int) {
        lastPromptTokens = tokens
        saveContext()
    }

    fun shouldCompress(estimatedChars: Int = 0): Boolean {
        val threshold = (currentModel.contextWindow * CONTEXT_PERCENT).toInt()
        val effectiveTokens = maxOf(lastPromptTokens, estimatedChars / 4)
        return effectiveTokens >= threshold
    }

    fun getMessagesToSummarize(): List<LogEntry> {
        val keepCount = 3
        return if (history.size > keepCount) history.dropLast(keepCount) else emptyList()
    }

    fun applySummary(newSummary: String, summarizedCount: Int) {
        summary = newSummary
        repeat(summarizedCount) { if (history.isNotEmpty()) history.removeAt(0) }
        saveContext()
    }

    fun buildContextContent(): String {
        val hasSummary = summary.isNotBlank()
        val hasHistory = history.isNotEmpty()
        if (!hasSummary && !hasHistory) return ""
        return buildString {
            if (hasSummary) {
                appendLine("Сводка предыдущего разговора:")
                appendLine(summary)
                if (hasHistory) appendLine()
            }
            if (hasHistory) {
                appendLine("История сообщений:")
                history.forEach { entry ->
                    val content = try {
                        json.decodeFromString<StructuredResponse>(entry.apiResponse).content
                    } catch (_: Exception) {
                        entry.apiResponse
                    }
                    appendLine("Вопрос: ${entry.userInput}")
                    appendLine("Ответ: $content")
                    appendLine()
                }
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
                        summary = summary,
                        agentMode = currentMode,
                        lastPromptTokens = lastPromptTokens,
                        userMessageCount = userMessageCount
                    )
                )
            )
        }
    }
}
