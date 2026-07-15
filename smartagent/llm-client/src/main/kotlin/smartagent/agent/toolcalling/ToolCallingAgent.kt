package smartagent.agent.toolcalling

import java.util.concurrent.ConcurrentHashMap
import smartagent.Colors
import smartagent.LLMGateway
import smartagent.ModelConfig
import smartagent.conversation.ChatHistory
import smartagent.conversation.ContextWindowGuard
import smartagent.conversation.MessageSummarizer
import smartagent.doc.DocGitContext
import smartagent.mcp_handler.McpManager

object ToolCallingAgent {

    private val histories = ConcurrentHashMap<Long, ChatHistory>()
    private val contextWindowGuard = ContextWindowGuard()

    fun clearHistory(chatId: Long? = null) {
        if (chatId == null) histories.clear()
        else histories.remove(chatId)
    }

    fun handle(
        query: String,
        gateway: LLMGateway,
        model: ModelConfig,
        chatId: Long? = null,
        options: smartagent.OllamaOptions? = null,
        ragContext: String? = null,
        gitContext: DocGitContext? = null,
        systemErrors: List<String> = emptyList(),
        extraSystemPrompt: String? = null,
        maxIterations: Int = 4
    ): String {
        val chatHistory = histories.getOrPut(chatId ?: 0L) { ChatHistory() }

        val toSummarize = chatHistory.messagesToSummarize()
        if (toSummarize.isNotEmpty() && contextWindowGuard.needsCompression(chatHistory.buildContextMessages(), model.contextWindow)) {
            val newSummary = MessageSummarizer.summarize(chatHistory.summary, toSummarize, gateway, model)
            chatHistory.applySummary(newSummary)
        }

        val connectedSessions = McpManager.allServers
            .mapNotNull { cfg -> McpManager.getSession(cfg.name)?.takeIf { it.isConnected }?.let { cfg.name to it } }
            .toMap()

        if (connectedSessions.isEmpty()) {
            val msg = "No MCP server connected. Run: /mcp <name> init"
            println("${Colors.LIGHT_YELLOW}$msg${Colors.RESET}")
            return msg
        }

        val loop = ToolCallingLoop(
            connectedSessions, gateway, model,
            maxIterations = maxIterations,
            chatId = chatId, options = options,
            ragContext = ragContext, gitContext = gitContext,
            systemErrors = systemErrors,
            extraSystemPrompt = extraSystemPrompt
        )
        val answer = loop.run(query, chatHistory.buildContextMessages())

        chatHistory.addExchange(query, answer)

        return answer
    }
}
