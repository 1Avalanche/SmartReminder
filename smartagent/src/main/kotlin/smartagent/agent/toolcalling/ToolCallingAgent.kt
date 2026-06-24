package smartagent.agent.toolcalling

import smartagent.Colors
import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import smartagent.mcp_handler.McpManager

object ToolCallingAgent {

    private val history = mutableListOf<Message>()

    fun clearHistory() = history.clear()

    fun handle(query: String, gateway: LLMGateway, model: ModelConfig) {
        val (serverName, session) = McpManager.allServers
            .mapNotNull { cfg -> McpManager.getSession(cfg.name)?.let { cfg.name to it } }
            .firstOrNull { (_, s) -> s.isConnected }
            ?: run {
                println("${Colors.LIGHT_YELLOW}No MCP server connected. Run: /mcp <name> init${Colors.RESET}")
                return
            }

        val loop = ToolCallingLoop(serverName, session, gateway, model)
        val answer = loop.run(query, history)

        history += Message("user", query)
        history += Message("assistant", answer)

        println()
        println("${Colors.LIGHT_VIOLET}$answer${Colors.RESET}")
        println()
    }
}
