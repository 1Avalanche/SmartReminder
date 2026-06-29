package smartagent

import kotlinx.coroutines.runBlocking
import smartagent.telegram.bot.TelegramBotRunner

fun main() {
    val token = System.getenv("TELEGRAM_BOT_TOKEN")
        ?: error("TELEGRAM_BOT_TOKEN env var not set")
    val gateway = OkHttpLLMGateway()
    val model = ModelConfig.DEEPSEEK
    runBlocking {
        TelegramBotRunner(token, gateway, model).start(this)
    }
}
