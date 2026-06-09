package cli

import kotlinx.serialization.encodeToString

internal class ChatSession {
    var currentModel: ModelConfig = ModelConfig.DEEPSEEK
        private set

    private val messages = mutableListOf(Message("system", SYSTEM_PROMPT))
    private val history = mutableListOf<LogEntry>()

    fun switchModel(model: ModelConfig) {
        currentModel = model
    }

    fun clear() {
        messages.clear()
        messages.add(Message("system", SYSTEM_PROMPT))
        history.clear()
        NetworkLogger.clear()
    }

    fun addUserMessage(text: String) {
        messages.add(Message("user", text))
    }

    fun addLogEntry(entry: LogEntry) {
        history.add(entry)
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
        val summaries = lastTen.joinToString("; ") { it.summary }
        return listOf(
            Message("assistant", "keywords: $keywords"),
            Message("assistant", "ранее пользователь спрашивал о: $summaries")
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
