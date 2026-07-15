package smartagent.tools.github

import smartagent.mcp_handler.McpSession
import smartagent.mcp_handler.renderToolResult
import smartagent.tools.Tool

class GitHubGetPRFilesTool(private val session: McpSession) : Tool {
    override val id = "github_get_pr_files"
    override val description = "Get list of changed files for a GitHub pull request"

    override fun execute(args: Map<String, Any>): String {
        val result = session.callTool("get_pull_request_files", args.toJsonArgs()) ?: return ""
        return renderToolResult(result)
    }
}
