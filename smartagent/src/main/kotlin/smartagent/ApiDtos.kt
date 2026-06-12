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
data class StructuredResponse(
    val summaryRequest: String = "",
    val summaryResponse: String = "",
    val content: String = "",
    val summary: String = ""
)

@Serializable
internal data class TokenEntry(
    val request: Int,
    val prompt: Int,
    val completion: Int,
    val total: Int
)

enum class CompressionMode { NONE, COMPRESS }

@Serializable
internal data class ContextFile(
    val history: List<LogEntry> = emptyList(),
    val summary: String = "",
    val compressionMode: CompressionMode = CompressionMode.NONE,
    val agentMode: AgentMode = AgentMode.CHAT
)

internal val json = Json { ignoreUnknownKeys = true }
