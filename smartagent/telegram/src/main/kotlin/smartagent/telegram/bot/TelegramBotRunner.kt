package smartagent.telegram.bot

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import smartagent.LLMGateway
import smartagent.ModelConfig
import smartagent.agent.assist.AssistOrchestrator
import smartagent.doc.KnowledgeService
import smartagent.mcp_handler.McpManager
import smartagent.support.SupportOrchestrator
import smartagent.telegram.client.TelegramApiClient
import smartagent.tools.ToolRegistry
import smartagent.telegram.review.TelegramReviewHandler
import smartagent.tools.github.GitHubGetDiffTool
import smartagent.tools.github.GitHubGetFileContentsTool
import smartagent.tools.github.GitHubListBranchesTool
import smartagent.tools.github.GitHubListCommitsTool
import smartagent.tools.github.GitHubSearchCodeTool

class TelegramBotRunner(
    private val client: TelegramApiClient,
    private val gateway: LLMGateway,
    private val model: ModelConfig,
    private val assistOrchestrator: AssistOrchestrator,
    private val supportOrchestrator: SupportOrchestrator,
    private val knowledgeService: KnowledgeService
) {
    private val requestChannel = Channel<Pair<Long, String>>(Channel.UNLIMITED)
    private val pendingCount = AtomicInteger(0)
    private val reviewHandler = TelegramReviewHandler(gateway, knowledgeService)

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            for ((chatId, text) in requestChannel) {
                when {
                    text.startsWith("/init ") || text == "/init" ->
                        client.sendMessage(chatId, handleInitCommand(text))
                    text.startsWith("/review") ->
                        handleReviewWithProgress(chatId, text)
                    text.startsWith("/") ->
                        client.sendMessage(chatId, "Неизвестная команда: $text")
                    else ->
                        client.sendMessage(chatId, supportOrchestrator.handle(query = text, model = model, chatId = chatId))
                }
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

                    val count = pendingCount.getAndIncrement()
                    requestChannel.send(chatId to text)
                    if (count > 0) {
                        client.sendMessage(chatId, "Твой вопрос поставлен в очередь на обработку.")
                    }
                }
            }
        }
    }

    private suspend fun handleReviewWithProgress(chatId: Long, text: String) {
        val parsed = reviewHandler.parseCommand(text)
        if (parsed == null) {
            client.sendMessage(chatId, reviewHandler.parseErrorMessage(text))
            return
        }
        client.sendMessage(chatId, "Начинаю ревью ${parsed.owner}/${parsed.repo} ${parsed.prNumber}")
        reviewHandler.runAndPublish(parsed.owner, parsed.repo, parsed.prNumber)
            .onSuccess { result ->
                client.sendMessage(chatId, reviewHandler.formatTelegramSummary(result))
            }
            .onFailure { e ->
                client.sendMessage(chatId, "Ошибка ревью: ${e.message}")
            }
    }

    private fun handleInitCommand(text: String): String {
        if (text == "/init") return "Использование: /init <owner>/<repo> [--branch <ветка>] [путь1] [путь2...]"

        val args = text.removePrefix("/init ").trim().split("\\s+".toRegex())
        val ownerRepo = args.firstOrNull() ?: return "Использование: /init <owner>/<repo>"
        val parts = ownerRepo.split("/")
        if (parts.size != 2) return "Формат: <owner>/<repo>"
        val (owner, repo) = parts

        val branchIdx = args.indexOf("--branch")
        val branch = if (branchIdx >= 0) args.getOrElse(branchIdx + 1) { "main" } else "main"
        val paths = args.drop(1).filter { it != "--branch" && it != branch }.ifEmpty { listOf(".") }

        val session = McpManager.getSession("github")
            ?: return "GitHub MCP не подключён. Добавь GITHUB_PERSONAL_ACCESS_TOKEN и перезапусти бота."
        ToolRegistry.register(GitHubGetFileContentsTool(session))
        ToolRegistry.register(GitHubSearchCodeTool(session))
        ToolRegistry.register(GitHubListCommitsTool(session))
        ToolRegistry.register(GitHubGetDiffTool(session))
        ToolRegistry.register(GitHubListBranchesTool(session))

        return runCatching {
            knowledgeService.init(owner, repo, branch, paths)
            "Индекс инициализирован: $owner/$repo@$branch"
        }.getOrElse { e -> "Ошибка индексации: ${e.message ?: e::class.simpleName}" }
    }
}
