package smartagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Message(val role: String, val content: String)

@Serializable
data class ChatRequest(val model: String, val messages: List<Message>, val transforms: List<String> = emptyList())

@Serializable
data class Choice(val message: Message, val index: Int = 0)

@Serializable
data class Usage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0
)

@Serializable
data class ChatResponse(val choices: List<Choice>, val usage: Usage? = null)

@Serializable
data class Fact(val name: String, val value: String)

@Serializable
data class StructuredResponse(
    val summaryRequest: String = "",
    val summaryResponse: String = "",
    val content: String = "",
    val facts: List<Fact> = emptyList()
)

@Serializable
internal data class TokenEntry(
    val request: Int,
    val prompt: Int,
    val completion: Int,
    val total: Int
)

@Serializable
enum class ContextStrategy(val formatInstruction: String) {
    SLIDING_WINDOW(
        """
В ответ всегда возвращай только JSON без пояснений и без markdown-разметки с полями:
- content (обязательно) — ответ, который нужно отобразить пользователю.
        """.trimIndent()
    ),
    STICKY_FACTS(
        """
В ответ всегда возвращай только JSON без пояснений и без markdown-разметки с полями:
- content (обязательно) — ответ, который нужно отобразить пользователю.
- facts (обязательно) — массив объектов {"name": "...", "value": "..."} с краткими фактами диалога (цели, ограничения, предпочтения и т.д.). Обновляй факты при каждом ответе.
        """.trimIndent()
    ),
    BRANCHING(
        """
В ответ всегда возвращай только JSON без пояснений и без markdown-разметки с полями:
- content (обязательно) — ответ, который нужно отобразить пользователю.
        """.trimIndent()
    );  // TODO: branching strategy

    companion object {
        fun fromName(name: String): ContextStrategy? = entries.find {
            it.name.equals(name.replace("-", "_"), ignoreCase = true)
        }
    }
}

@Serializable
internal data class Branch(val name: String, val history: List<LogEntry>)

@Serializable
internal data class ContextFile(
    val history: List<LogEntry> = emptyList(),
    val summary: String = "",
    val contextStrategy: ContextStrategy = ContextStrategy.SLIDING_WINDOW,
    val agentMode: AgentMode = AgentMode.CHAT,
    val facts: List<Fact> = emptyList(),
    val branches: List<Branch> = emptyList(),
    val activeBranch: String? = null
)

internal val json = Json { ignoreUnknownKeys = true }
