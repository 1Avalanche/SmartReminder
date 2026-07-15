package smartagent.telegram.review

import smartagent.LLMGateway
import smartagent.ModelConfig
import smartagent.doc.KnowledgeService
import smartagent.mcp_handler.McpManager
import smartagent.review.GitHubReviewDataProvider
import smartagent.review.GitHubReviewPublisher
import smartagent.review.ReviewAgent

open class TelegramReviewHandler(
    private val gateway: LLMGateway,
    private val knowledgeService: KnowledgeService
) {
    data class ParsedCommand(val owner: String, val repo: String, val prNumber: Int)

    fun parseCommand(text: String): ParsedCommand? {
        val args = text.trim().removePrefix("/review").trim().split("\\s+".toRegex())
        val ownerRepo = args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val parts = ownerRepo.split("/")
        if (parts.size != 2) return null
        val (owner, repo) = parts
        val prNumber = args.getOrNull(1)?.toIntOrNull() ?: return null
        return ParsedCommand(owner, repo, prNumber)
    }

    fun parseErrorMessage(text: String): String {
        val args = text.trim().removePrefix("/review").trim().split("\\s+".toRegex())
        val ownerRepo = args.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: return "Использование: /review <owner>/<repo> <номер_pr>"
        if (ownerRepo.split("/").size != 2) return "Формат: <owner>/<repo>"
        args.getOrNull(1)?.toIntOrNull() ?: return "Номер PR должен быть числом"
        return "Неверная команда"
    }

    // Runs full review flow and returns the markdown that was posted as a comment.
    open fun runAndPublish(owner: String, repo: String, prNumber: Int): Result<String> {
        val session = McpManager.getSession("github")
            ?: return Result.failure(Exception("GitHub MCP не подключён. Добавь GITHUB_PERSONAL_ACCESS_TOKEN."))

        val context = runCatching {
            GitHubReviewDataProvider(session).fetchContext(owner, repo, prNumber, knowledgeService)
        }.getOrElse { e -> return Result.failure(e) }

        val report = runCatching {
            ReviewAgent(gateway).review(context, ModelConfig.QWEN)
        }.getOrElse { e -> return Result.failure(e) }

        val publisher = GitHubReviewPublisher(session)
        runCatching {
            publisher.publish(report)
        }.getOrElse { e -> return Result.failure(e) }

        return Result.success(publisher.toMarkdown(report))
    }

    // Used by the API path (fire-and-forget, result is discarded).
    fun handleText(text: String): String {
        val parsed = parseCommand(text) ?: return parseErrorMessage(text)
        return runAndPublish(parsed.owner, parsed.repo, parsed.prNumber)
            .fold(onSuccess = { "ok" }, onFailure = { e -> "Ошибка: ${e.message}" })
    }
}
