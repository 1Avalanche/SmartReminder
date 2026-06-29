package smartagent.telegram.bot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import smartagent.LLMGateway
import smartagent.ModelConfig
import smartagent.agent.toolcalling.ToolCallingAgent
import smartagent.telegram.client.TelegramApiClient

class TelegramBotRunner(
    token: String,
    private val gateway: LLMGateway,
    private val model: ModelConfig
) {
    private val client = TelegramApiClient(token)

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            var offset = 0L
            println("[TelegramBot] Listening for messages...")
            while (true) {
                val updates = runCatching { client.getUpdates(offset) }.getOrElse { emptyList() }
                for (update in updates) {
                    offset = update.updateId + 1
                    val text = update.message?.text ?: continue
                    val chatId = update.message.chat.id
                    // sequential — preserves shared ToolCallingAgent history consistency
                    val answer = ToolCallingAgent.handle(
                        query = text,
                        gateway = gateway,
                        model = model,
                        chatId = chatId
                    )
                    client.sendMessage(chatId, answer)
                }
            }
        }
    }
}
