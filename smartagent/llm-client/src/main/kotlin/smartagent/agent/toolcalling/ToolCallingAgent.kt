package smartagent.agent.toolcalling

import smartagent.Colors
import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import smartagent.mcp_handler.McpManager

object ToolCallingAgent {

    private val history = mutableListOf<Message>()

    fun clearHistory() = history.clear()

    fun handle(
        query: String,
        gateway: LLMGateway,
        model: ModelConfig,
        chatId: Long? = null
    ): String {
        val connectedSessions = McpManager.allServers
            .mapNotNull { cfg -> McpManager.getSession(cfg.name)?.takeIf { it.isConnected }?.let { cfg.name to it } }
            .toMap()

        if (connectedSessions.isEmpty()) {
            val msg = "No MCP server connected. Run: /mcp <name> init"
            println("${Colors.LIGHT_YELLOW}$msg${Colors.RESET}")
            return msg
        }

        val loop = ToolCallingLoop(connectedSessions, gateway, model, chatId = chatId)
        val answer = loop.run(query, history)

        history += Message("user", query)
        history += Message("assistant", answer)

        println()
        println("${Colors.LIGHT_VIOLET}$answer${Colors.RESET}")
        println()

        return answer
    }
}
