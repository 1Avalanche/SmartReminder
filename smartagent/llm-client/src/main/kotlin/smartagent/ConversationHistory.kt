package smartagent

private const val KEEP_RECENT = 3
private const val PROFILE_TRIGGER_EVERY_N_MESSAGES = 3

class ConversationHistory(private val onChange: () -> Unit = {}) {
    private val entries = mutableListOf<LogEntry>()
    var summary: String = ""
        private set
    var userMessageCount: Int = 0
        private set
    private val fileContext = mutableListOf<Pair<String, String>>()

    fun addLogEntry(entry: LogEntry) {
        entries.add(entry)
        userMessageCount++
        onChange()
    }

    fun getHistory(): List<LogEntry> = entries.toList()

    fun getLastUserInputs(n: Int): List<String> = entries.takeLast(n).map { it.userInput }

    fun shouldTriggerProfile(): Boolean = userMessageCount > 0 && userMessageCount % PROFILE_TRIGGER_EVERY_N_MESSAGES == 0

    fun getMessagesToSummarize(): List<LogEntry> =
        if (entries.size > KEEP_RECENT) entries.dropLast(KEEP_RECENT) else emptyList()

    fun applySummary(newSummary: String, summarizedCount: Int) {
        summary = newSummary
        repeat(summarizedCount) { if (entries.isNotEmpty()) entries.removeAt(0) }
        onChange()
    }

    fun buildContextContent(): String {
        val hasSummary = summary.isNotBlank()
        val hasHistory = entries.isNotEmpty()
        if (!hasSummary && !hasHistory) return ""
        return buildString {
            if (hasSummary) {
                appendLine("Сводка предыдущего разговора:")
                appendLine(summary)
                if (hasHistory) appendLine()
            }
            if (hasHistory) {
                appendLine("История сообщений:")
                entries.forEach { entry ->
                    val content = try {
                        json.decodeFromString<StructuredResponse>(entry.apiResponse).content
                    } catch (_: Exception) { entry.apiResponse }
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
        } catch (_: Exception) { Pair(raw, null) }
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

    fun clear() {
        entries.clear()
        fileContext.clear()
        summary = ""
        userMessageCount = 0
    }

    internal fun loadFrom(loaded: List<LogEntry>, summary: String, userMessageCount: Int) {
        entries.clear()
        entries.addAll(loaded)
        this.summary = summary
        this.userMessageCount = userMessageCount
    }
}
