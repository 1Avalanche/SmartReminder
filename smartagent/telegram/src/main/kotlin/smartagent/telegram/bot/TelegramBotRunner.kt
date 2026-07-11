package smartagent.telegram.bot

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import smartagent.LLMGateway
import smartagent.ModelConfig
import smartagent.OllamaOptions
import smartagent.agent.toolcalling.ToolCallingAgent
import smartagent.telegram.auth.AuthManager
import smartagent.telegram.client.TelegramApiClient

class TelegramBotRunner(
    token: String,
    private val gateway: LLMGateway,
    private val model: ModelConfig,
    private val authManager: AuthManager
) {
    private val client = TelegramApiClient(token)
    private val requestChannel = Channel<Pair<Long, String>>(Channel.UNLIMITED)
    private val pendingCount = AtomicInteger(0)
    private val ollamaOptions = OllamaOptions(num_ctx = 8192, temperature = 0.2, num_predict = 512)

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            for ((chatId, text) in requestChannel) {
                val answer = ToolCallingAgent.handle(
                    query = text,
                    gateway = gateway,
                    model = model,
                    chatId = chatId,
                    options = ollamaOptions
                )
                client.sendMessage(chatId, answer)
                pendingCount.decrementAndGet()
            }
        }

        scope.launch(Dispatchers.IO) {
            var offset = 0L
            println("[TelegramBot] Listening for messages...")
            while (true) {
                val updates = runCatching { client.getUpdates(offset) }.getOrElse { emptyList() }
                for (update in updates) {
                    offset = update.updateId + 1
                    val text = update.message?.text ?: continue
                    val chatId = update.message.chat.id

                    if (!authManager.isAuthorized(chatId) && !authManager.isPendingAuth(chatId)) {
                        authManager.requestAuth(chatId)
                        client.sendMessage(chatId, "Введи ключ авторизации:")
                        continue
                    }

                    if (authManager.isPendingAuth(chatId)) {
                        if (authManager.tryAuthorize(chatId, text)) {
                            client.sendMessage(chatId, "Авторизация успешна!")
                        } else {
                            client.sendMessage(chatId, "Неверный ключ. Доступ запрещён.")
                        }
                        continue
                    }

                    val count = pendingCount.getAndIncrement()
                    requestChannel.send(chatId to text)
                    if (count > 0) {
                        client.sendMessage(chatId, "Твой вопрос поставлен в очередь на обработку.")
                    }
                }
            }
        }
    }
}
