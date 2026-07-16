package smartagent.telegram.review

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import smartagent.LLMGateway
import smartagent.ModelConfig
import smartagent.doc.KnowledgeService
import smartagent.mcp_handler.McpManager
import smartagent.review.GitHubReviewDataProvider
import smartagent.review.GitHubReviewPublisher
import smartagent.review.ReviewAgent
import smartagent.review.ReviewContext
import smartagent.review.ReviewReport
import smartagent.review.ReviewSeverity

open class TelegramReviewHandler(
    private val gateway: LLMGateway,
    private val knowledgeService: KnowledgeService
) {
    data class ParsedCommand(val owner: String, val repo: String, val prNumber: Int)

    data class ReviewResult(
        val context: ReviewContext,
        val report: ReviewReport,
        val markdown: String
    )

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

    open fun runAndPublish(owner: String, repo: String, prNumber: Int): Result<ReviewResult> {
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

        return Result.success(ReviewResult(context, report, publisher.toMarkdown(report)))
    }

    fun formatTelegramSummary(result: ReviewResult): String {
        val ctx = result.context
        val rep = result.report
        val files = parseChangedFiles(ctx.changedFiles)
        val counts = ReviewSeverity.entries.associateWith { sev ->
            rep.issues.count { it.severity == sev }
        }
        val totalIssues = rep.issues.size

        return buildString {
            appendLine("📦 ${ctx.owner}/${ctx.repo}")
            appendLine("🔀 PR #${rep.pullRequestNumber}: ${rep.prTitle}")
            appendLine("🌿 ${ctx.headBranch} → ${ctx.baseBranch}")
            appendLine()
            if (files.isNotEmpty()) {
                appendLine("📝 Изменённые файлы (${files.size}):")
                files.take(MAX_FILES_IN_SUMMARY).forEach { (name, status) ->
                    appendLine("• $name ($status)")
                }
                if (files.size > MAX_FILES_IN_SUMMARY) {
                    appendLine("  … ещё ${files.size - MAX_FILES_IN_SUMMARY}")
                }
                appendLine()
            }
            appendLine("💬 Общий вывод:")
            appendLine(rep.summary)
            appendLine()
            appendLine("🔍 Замечания: $totalIssues найдено")
            if (totalIssues > 0) {
                val line = ReviewSeverity.entries
                    .mapNotNull { sev -> counts[sev]?.takeIf { it > 0 }?.let { "${severityIcon(sev)} ${sev.name}: $it" } }
                    .joinToString("  ")
                appendLine(line)
            }
        }.trimEnd()
    }

    fun handleText(text: String): String {
        val parsed = parseCommand(text) ?: return parseErrorMessage(text)
        return runAndPublish(parsed.owner, parsed.repo, parsed.prNumber)
            .fold(onSuccess = { "ok" }, onFailure = { e -> "Ошибка: ${e.message}" })
    }

    private fun parseChangedFiles(filesJson: String): List<Pair<String, String>> {
        return try {
            Json.parseToJsonElement(filesJson).jsonArray.mapNotNull { el ->
                val obj = el.jsonObject
                val name = obj["filename"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val status = obj["status"]?.jsonPrimitive?.content ?: "changed"
                name to status
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun severityIcon(severity: ReviewSeverity) = when (severity) {
        ReviewSeverity.CRITICAL -> "🔴"
        ReviewSeverity.HIGH -> "🟠"
        ReviewSeverity.MEDIUM -> "🟡"
        ReviewSeverity.LOW -> "🔵"
        ReviewSeverity.INFO -> "⚪"
    }

    companion object {
        private const val MAX_FILES_IN_SUMMARY = 10
    }
}
