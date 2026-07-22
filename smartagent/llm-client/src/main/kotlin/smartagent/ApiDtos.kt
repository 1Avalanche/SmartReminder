package smartagent

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Message(val role: String, val content: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Reasoning(@EncodeDefault val enabled: Boolean = false)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OllamaOptions(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val num_ctx: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val temperature: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val num_predict: Int? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val transforms: List<String> = emptyList(),
    @EncodeDefault val reasoning: Reasoning = Reasoning(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val temperature: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val max_tokens: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val options: OllamaOptions? = null,
    @SerialName("reasoning_effort") @EncodeDefault(EncodeDefault.Mode.NEVER) val reasoningEffort: String? = null
)

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
    val content: String = ""
)

val json = Json { ignoreUnknownKeys = true }
val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }
