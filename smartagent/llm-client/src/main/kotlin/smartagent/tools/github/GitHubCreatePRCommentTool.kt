package smartagent.tools.github

import smartagent.mcp_handler.McpSession
import smartagent.mcp_handler.renderToolResult
import smartagent.tools.Tool

class GitHubCreatePRCommentTool(private val session: McpSession) : Tool {
    override val id = "github_create_pr_comment"
    override val description = "Post a review comment on a GitHub pull request"

    override fun execute(args: Map<String, Any>): String {
        val result = session.callTool("add_issue_comment", args.toJsonArgs()) ?: return ""
        return renderToolResult(result)
    }
}
