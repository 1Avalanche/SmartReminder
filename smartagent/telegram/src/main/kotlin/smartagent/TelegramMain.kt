package smartagent

import kotlinx.coroutines.runBlocking
import smartagent.mcp_handler.McpManager
import smartagent.telegram.auth.AuthManager
import smartagent.telegram.bot.TelegramBotRunner

fun main() {
    val token = System.getenv("TELEGRAM_BOT_TOKEN")
        ?: error("TELEGRAM_BOT_TOKEN env var not set")
    val authKey = System.getenv("TELEGRAM_AUTH_KEY")
        ?: error("TELEGRAM_AUTH_KEY env var not set")
    val gateway = OkHttpLLMGateway()
    val model = ModelConfig.TG_TUNNEL
    val authManager = AuthManager(authKey)
    runCatching { McpManager.initServer("my-mcp") }
        .onSuccess { println("[MCP] Connected: my-mcp") }
        .onFailure { println("[MCP] Failed to connect to my-mcp: ${it.message}") }
    runCatching { McpManager.initServer("tavily-mcp") }
        .onSuccess { println("[MCP] Connected: tavily-mcp") }
        .onFailure { println("[MCP] Failed to connect to tavily-mcp: ${it.message}") }
    runBlocking {
        TelegramBotRunner(token, gateway, model, authManager).start(this)
    }
}
