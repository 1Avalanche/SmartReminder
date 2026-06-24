package smartagent.telegram.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TelegramUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TelegramMessage? = null
)

@Serializable
data class TelegramMessage(
    @SerialName("message_id") val messageId: Long,
    val chat: TelegramChat,
    val text: String? = null
)

@Serializable
data class TelegramChat(val id: Long)

@Serializable
data class GetUpdatesResponse(val ok: Boolean, val result: List<TelegramUpdate>)

@Serializable
data class SendMessageRequest(
    @SerialName("chat_id") val chatId: Long,
    val text: String
)
