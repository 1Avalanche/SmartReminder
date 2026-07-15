package smartagent.review

import smartagent.mcp_handler.McpSession
import smartagent.tools.github.GitHubCreatePRCommentTool

class GitHubReviewPublisher(private val session: McpSession?) {

    fun publish(report: ReviewReport): String {
        val s = session ?: error("GitHub MCP session not available")
        val commentTool = GitHubCreatePRCommentTool(s)
        val markdown = toMarkdown(report)
        val args = mapOf(
            "owner" to report.owner,
            "repo" to report.repo,
            "issue_number" to report.pullRequestNumber,
            "body" to markdown
        )
        val result = commentTool.execute(args)
        if (result.isNotBlank() && !result.startsWith("[error]")) return result
        error("Failed to post review comment: ${result.ifBlank { "MCP returned empty result" }}")
    }

    fun toMarkdown(report: ReviewReport): String = buildString {
        appendLine("## 🔍 AI Code Review — PR #${report.pullRequestNumber}")
        appendLine()
        appendLine("**${report.prTitle}**")
        appendLine()
        appendLine("### Summary")
        appendLine(report.summary)
        appendLine()

        if (report.issues.isEmpty()) {
            appendLine("✅ No issues found.")
            return@buildString
        }

        val bySeverity = report.issues.groupBy { it.severity }

        for (severity in ReviewSeverity.entries) {
            val issues = bySeverity[severity] ?: continue
            appendLine("### ${severityIcon(severity)} ${severity.name} (${issues.size})")
            appendLine()
            for (issue in issues) {
                val location = if (issue.line != null) "`${issue.file}:${issue.line}`" else "`${issue.file}`"
                appendLine("**[${issue.category.name}]** $location")
                appendLine(issue.description)
                appendLine()
                appendLine("> **Fix:** ${issue.recommendation}")
                appendLine()
            }
        }

        appendLine("---")
        appendLine("*Generated at ${report.timestamp}*")
    }

    private fun severityIcon(severity: ReviewSeverity) = when (severity) {
        ReviewSeverity.CRITICAL -> "🔴"
        ReviewSeverity.HIGH -> "🟠"
        ReviewSeverity.MEDIUM -> "🟡"
        ReviewSeverity.LOW -> "🔵"
        ReviewSeverity.INFO -> "⚪"
    }
}
