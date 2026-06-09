package cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Message(val role: String, val content: String)

@Serializable
data class ChatRequest(val model: String, val messages: List<Message>)

@Serializable
data class Choice(val message: Message, val index: Int = 0)

@Serializable
data class ChatResponse(val choices: List<Choice>)

@Serializable
data class StructuredResponse(
    val keywords: List<String> = emptyList(),
    val summary: String = "",
    val content: String = ""
)

internal val json = Json { ignoreUnknownKeys = true }
